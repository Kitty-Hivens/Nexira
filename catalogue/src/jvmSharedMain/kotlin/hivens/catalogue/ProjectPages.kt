package hivens.catalogue

import hivens.catalogue.api.BuildPageView
import hivens.catalogue.api.DependencyKind
import hivens.catalogue.api.Disclosure
import hivens.catalogue.api.GameVersionGroup
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.InstallOffer
import hivens.catalogue.api.InstalledFile
import hivens.catalogue.api.LeftOut
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.ProjectCreator
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.VersionDependency
import hivens.catalogue.api.gameVersionChips
import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackInstance
import hivens.launcher.content.ContentKind
import hivens.launcher.content.folderName
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.DISABLED_SUFFIX
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.ModInstaller
import hivens.launcher.instance.loadersFor
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("hivens.catalogue.ProjectPage")

/**
 * The catalogue's whole Minecraft version list, newest first, asked once per process
 * and kept once it was read. All of it and not just the releases: folding needs to
 * know which entries are snapshots and which are the old alpha and beta lines.
 */
class GameVersionList(private val read: suspend () -> List<GameVersionTag>) {
    @Volatile
    private var cached: List<GameVersionTag>? = null

    /** The list, or empty where it could not be read, which leaves versions unfolded. */
    suspend fun get(): List<GameVersionTag> {
        cached?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching { read() }
                .onFailure { if (it is CancellationException) throw it else log.debug("game-version tags unavailable", it) }
                .getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) cached = fetched
        return fetched
    }
}

/** What a page needs of an installer: put a build into a pack, and say what a pack holds. */
interface PageInstall {
    suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome
    suspend fun presentProjects(dir: Path): Set<String>
}

/** The launcher's installer as a [PageInstall]. */
class InstallerPageInstall(private val installer: ModInstaller) : PageInstall {
    override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String) = installer.install(dir, version, mc, loader)
    override suspend fun presentProjects(dir: Path) = installer.presentProjects(dir)
}

/**
 * Everything one project page has to find out, as [ProjectPageView], and the one thing
 * it publishes, what the window's rail describes.
 *
 * One per page level. The page is addressed by a [ProjectTarget] and resolves the rest
 * here. A catalogue entry is fetched by id. A file is hashed and looked up, and when the
 * catalogue does not know the hash the page keeps its shape and draws what the archive
 * declared, or what the mirror's registry says of it.
 *
 * Each showing of the page is a visit of its own, named by the screen, and starts from
 * nothing, the way a page drawn anew does. The work of a visit stops when it is hidden.
 * An install runs on [work], the app's, so leaving the screen between the jar that was
 * clicked and what it requires does not leave a mod without them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectPageModel(
    private val modrinth: ModrinthClient,
    private val packOf: suspend (String) -> PackInstance?,
    private val instancesDir: Path,
    private val scanner: InstanceContentScanner?,
    private val open: OpenProjects,
    private val versionTags: GameVersionList,
    scope: CoroutineScope,
    work: CoroutineScope,
    /** Who to ask about a file the catalogue cannot name. Null keeps the archive's own word. */
    private val mirrorLookup: MirrorLookup? = null,
    /** How the page installs and asks what a pack holds. Null offers no install. */
    private val pageInstall: PageInstall? = null,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(scope.coroutineContext + serial)
    private val work = CoroutineScope(work.coroutineContext + serial)

    private val _view = MutableStateFlow<ProjectPageView?>(null)
    val view: StateFlow<ProjectPageView?> = _view.asStateFlow()

    /** Where the page can put things, and what runs there. */
    internal class Destination(val name: String, val dir: Path, val mc: String, val loader: String)

    /**
     * One showing of the page and what it read. Each visit keeps its own, so work of a
     * visit that is over writes into that visit and never into the next.
     */
    private class Visit(val id: String, val target: ProjectTarget) {
        var project: ModrinthProject? = null
        var mirror: MirrorMod? = null
        var installed: InstalledContent? = null
        var disclosures: List<Disclosure> = emptyList()
        var creators: List<ProjectCreator> = emptyList()
        var builds: List<ModrinthVersion>? = null
        var destination: Destination? = null

        /** The build a row asked for, while that install is the last one, which is what a retry repeats. */
        var chosenBuild: ModrinthVersion? = null

        val jobs = ArrayList<Job>()
    }

    @Volatile
    private var current: Visit? = null

    /** The visit shown now, or null before one is. */
    val visit: String? get() = current?.id

    /** The project id the page resolved to, once it has. */
    internal val projectId: String? get() = current?.project?.id

    /**
     * Shows the page for [visit] of [target]. A new visit starts from nothing at once,
     * so a screen drawn right after reads it, and its reads start behind it.
     */
    fun show(target: ProjectTarget, visit: String) {
        val fresh = synchronized(this) {
            if (current?.id == visit) null else Visit(visit, target).also { next ->
                current?.let { old -> synchronized(old.jobs) { old.jobs.forEach { it.cancel() } } }
                current = next
                _view.value = ProjectPageView(
                    target = target,
                    visit = visit,
                    installPossible = target is ProjectTarget.Catalogue && pageInstall != null,
                )
            }
        }
        open.claim(visit)
        fresh?.let { v -> v.launch { load(v) } }
    }

    /** The visit [visit] left the screen: its reads stop, and the rail goes back to what it showed before. */
    fun hide(visit: String) {
        current?.takeIf { it.id == visit }?.let { v -> synchronized(v.jobs) { v.jobs.forEach { it.cancel() } } }
        open.release(visit)
    }

    /** Reads the page again, after a lookup that did not run. */
    fun reload() {
        current?.let { v -> v.launch { load(v) } }
    }

    /** Runs [block] for [this] visit on the page's work, stopped with the visit. */
    private fun Visit.launch(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        synchronized(jobs) { jobs += job }
    }

    /** Applies [change] to the view of [visit], and nothing where another visit is shown now. */
    private fun edit(visit: Visit, change: (ProjectPageView) -> ProjectPageView) {
        _view.update { view -> if (view?.visit == visit.id) change(view) else view }
    }

    /** Sets what a render or a test stands a page up with, the way a load would leave it. */
    internal fun seed(
        project: ModrinthProject?,
        /** The project's builds as a listing would leave them, for an install that names one. */
        builds: List<ModrinthVersion>? = null,
        view: (ProjectPageView) -> ProjectPageView = { it },
    ) {
        val v = current ?: return
        v.project = project
        v.builds = builds
        edit(v) { view(it.copy(project = project?.toDetails(), loading = false)) }
    }

    private suspend fun load(v: Visit) {
        edit(v) { it.copy(loading = true, failed = false) }
        // Published before the fetch: the rail's blocks get their shape on the first frame
        // from the name the route carries, and fill in.
        publish(v)
        var failed = false
        try {
            when (val t = v.target) {
                is ProjectTarget.Catalogue -> loadCatalogue(v, t.projectId)
                is ProjectTarget.Installed -> loadInstalled(v, t)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A page that failed to load and a project that declares nothing must not
            // look the same, so the failure is kept rather than folded into an empty result.
            log.warn("Project page: could not resolve {}", v.target.key, e)
            failed = true
            edit(v) { it.copy(failed = true) }
        } finally {
            // Restored even when the visit was left mid-flight, so nothing is left spinning.
            edit(v) { it.copy(loading = false) }
        }
        // Nothing read is nothing to show. A file on disk still has its own facts when
        // only the catalogue failed.
        if (failed && v.installed == null) open.publish(v.id, null) else publish(v)
    }

    private suspend fun loadCatalogue(v: Visit, projectId: String) {
        val found = withContext(Dispatchers.IO) { modrinth.resolveProject(projectId) }
        v.project = found
        edit(v) { it.copy(project = found.toDetails()) }
        // The rail fills with the header rather than seconds after it.
        publish(v)
        v.disclosures = fetchDisclosures(projectId)
        v.creators = fetchCreators(projectId)
        val offer = resolveInstall(v, projectId)
        edit(v) { it.copy(install = offer) }
    }

    /**
     * Whether this project can be put into the pack the person came from, and whether it
     * is already there, asked of the folder rather than remembered from a click.
     */
    private suspend fun resolveInstall(v: Visit, projectId: String): InstallOffer {
        val installer = pageInstall ?: return InstallOffer.None
        val pack = resolveDestination(v) ?: return if (v.target is ProjectTarget.Catalogue) InstallOffer.Choose else InstallOffer.None
        val present = withContext(Dispatchers.IO) {
            runCatching { installer.presentProjects(pack.dir) }
                .onFailure { if (it is CancellationException) throw it else log.debug("Project page: could not read what {} holds", pack.name, it) }
                .getOrDefault(emptySet())
        }
        return if (projectId in present) InstallOffer.Present(pack.name) else InstallOffer.Install(pack.name)
    }

    /**
     * The pack this page can act on, resolved once a visit and kept, so the install and
     * the versions tab cannot disagree about which loader the pack runs.
     */
    private suspend fun resolveDestination(v: Visit): Destination? {
        v.destination?.let { return it }
        val id = v.target.packId ?: return null
        val pack = packOf(id) ?: return null
        val resolved = Destination(
            name = pack.displayName,
            dir = instancesDir.resolve(pack.instanceDirName),
            mc = pack.cachedManifest?.minecraftVersion.orEmpty(),
            loader = pack.cachedManifest?.loaderName
                ?.takeIf { it.isNotBlank() && !it.equals(VANILLA, ignoreCase = true) }
                ?.lowercase()
                .orEmpty(),
        )
        v.destination = resolved
        // Every loader a build may be published for and still have a place in the pack:
        // its mods, and the resource packs and shaders any pack takes.
        val loaders = ContentKind.entries.flatMap { loadersFor(it, resolved.loader, resolved.mc) }
        edit(v) { it.copy(packMcVersion = resolved.mc, packLoader = resolved.loader, packLoaders = loaders) }
        return resolved
    }

    /** Fetches the build list, once per visit, when a tab that lists them is first opened. */
    fun loadVersions() {
        current?.let { v -> v.launch { readVersions(v) } }
    }

    /** Reads the build list again after a listing that failed. */
    fun retryVersions() {
        val v = current ?: return
        v.launch {
            v.builds = null
            edit(v) { it.copy(versions = null) }
            readVersions(v)
        }
    }

    /** Asks for the folding order without asking for the build list, for a page that shows one build. */
    fun loadGameVersionTags() {
        val v = current ?: return
        v.launch {
            if (_view.value?.gameVersionTags.isNullOrEmpty()) {
                val tags = versionTags.get()
                edit(v) { it.copy(gameVersionTags = tags) }
            }
        }
    }

    private suspend fun readVersions(v: Visit) {
        // No project means nothing to ask: the page is still resolving one, and the
        // caller asks again when it arrives, or the catalogue has never seen the file.
        val projectId = v.project?.id ?: return
        val shown = _view.value?.takeIf { it.visit == v.id } ?: return
        if (shown.versions != null || shown.versionsLoading) return
        edit(v) { it.copy(versionsLoading = true, versionsFailed = false) }
        try {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { modrinth.listVersions(projectId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        log.warn("Project page: listing versions of {} failed", projectId, it)
                    }
                    .getOrNull()
            }
            if (fetched == null) {
                edit(v) { it.copy(versionsFailed = true) }
            } else {
                v.builds = fetched
                edit(v) { it.copy(versions = fetched.map { b -> b.toBuild() }) }
            }
            // The table folds each build's game versions, and folding needs the catalogue's release order.
            if (_view.value?.gameVersionTags.isNullOrEmpty()) {
                val tags = versionTags.get()
                edit(v) { it.copy(gameVersionTags = tags) }
            }
        } finally {
            edit(v) { it.copy(versionsLoading = false) }
        }
    }

    /**
     * Installs a build: the one [versionId] names, or with null the newest that runs on
     * the pack. A build marked as not running there is still installable, since the
     * person is the one looking at both.
     */
    fun install(versionId: String?) {
        val v = current ?: return
        work.launch { if (versionId == null) installNewest(v) else installBuild(v, versionId) }
    }

    /** Repeats the last install as it was asked for: the build a row named, else the newest that fits. */
    fun retryInstall() {
        val v = current ?: return
        work.launch {
            val chosen = v.chosenBuild
            if (chosen != null) installVersion(v, chosen) else installNewest(v)
        }
    }

    private suspend fun installBuild(v: Visit, versionId: String) {
        val projectId = v.project?.id ?: return
        val version = v.builds?.firstOrNull { it.id == versionId }
            ?: runCatching { withContext(Dispatchers.IO) { modrinth.resolveVersion(projectId, versionId) } }
                .onFailure { if (it is CancellationException) throw it else log.warn("Project page: could not read build {}", versionId, it) }
                .getOrNull()
            ?: return edit(v) { it.copy(installFailed = true) }
        installVersion(v, version)
    }

    private fun startInstall(v: Visit) = edit(v) {
        it.copy(installing = true, installFailed = false, installNoBuild = false, installMissing = emptyList(), installLeftOut = emptyList(), installRefusal = null)
    }

    private suspend fun installVersion(v: Visit, version: ModrinthVersion) {
        if (_view.value?.takeIf { it.visit == v.id }?.installing == true) return
        val installer = pageInstall ?: return
        val pack = resolveDestination(v) ?: return
        v.chosenBuild = version
        startInstall(v)
        try {
            val outcome = withContext(Dispatchers.IO) { installer.install(pack.dir, version, pack.mc, pack.loader) }
            record(v, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing build {} from the project page failed", version.id, e)
            edit(v) { it.copy(installFailed = true) }
        } finally {
            edit(v) { it.copy(installing = false) }
        }
    }

    /**
     * Downloads the best build for the pack the page was opened from: the newest that
     * runs on the pack's game version and loader. A project with no such build is not
     * a failure, it exists and does not support this pack.
     */
    private suspend fun installNewest(v: Visit) {
        val shown = _view.value?.takeIf { it.visit == v.id } ?: return
        if (shown.install !is InstallOffer.Install || shown.installing) return
        val projectId = v.project?.id ?: return
        val installer = pageInstall ?: return
        v.chosenBuild = null
        startInstall(v)
        try {
            val pack = resolveDestination(v) ?: return
            val version = withContext(Dispatchers.IO) { modrinth.newestMatchingVersion(projectId, pack.mc, pack.loader) }
            if (version == null) {
                // Refused rather than substituted. The person can still take one by name.
                log.info("Project {} has no build for {} / {}", projectId, pack.mc, pack.loader)
                edit(v) { it.copy(installNoBuild = true) }
                return
            }
            val outcome = withContext(Dispatchers.IO) { installer.install(pack.dir, version, pack.mc, pack.loader) }
            record(v, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing {} from the project page failed", projectId, e)
            edit(v) { it.copy(installFailed = true) }
        } finally {
            edit(v) { it.copy(installing = false) }
        }
    }

    /** What an install's outcome means for the page. */
    private suspend fun record(v: Visit, outcome: ModInstaller.Outcome) {
        val pack = v.destination ?: return
        val headId = v.project?.id
        // The build asked for, left out for a reason the install could name. That is an
        // answer and reads as one under the button: Retry for a file the pack itself
        // owns repeats a click that can never land.
        val headReason = outcome.headLeftOut
        val title = _view.value?.takeIf { it.visit == v.id }?.title.orEmpty()
        val leftOut = outcome.skips
            .filter { it !is ContentInstaller.Skip.Present && it !is ContentInstaller.Skip.AlreadyInstalled && (it.projectId != headId || it === headReason) }
            .map { skip -> LeftOut(if (skip === headReason) title else titleOf(skip.projectId), skip.toReason()) }
        edit(v) {
            val refused = it.copy(installRefusal = outcome.refusal?.toView(), installLeftOut = leftOut)
            when {
                outcome.ok -> refused.copy(install = InstallOffer.Present(pack.name), installMissing = outcome.missing)
                headReason != null && outcome.refusal == null -> refused
                else -> refused.copy(installFailed = true)
            }
        }
    }

    /** A project's name for a sentence, its id when the catalogue cannot say. */
    private suspend fun titleOf(projectId: String?): String {
        val id = projectId ?: return "?"
        return withContext(Dispatchers.IO) {
            runCatching { modrinth.resolveProject(id).title }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }?.takeIf { it.isNotBlank() } ?: id
    }

    private suspend fun loadInstalled(v: Visit, t: ProjectTarget.Installed) {
        val pack = packOf(t.instanceId)
        val dir = pack?.let { instancesDir.resolve(it.instanceDirName) }
        val kind = t.kind.toContentKind()
        val file = dir?.let { locate(it, kind, t.fileName) } ?: return
        val read = withContext(Dispatchers.IO) { runCatching { scanner?.read(file, kind) }.getOrNull() }
        v.installed = read
        edit(v) { view -> view.copy(file = read?.let { InstalledFile(it.displayName, it.version, it.description, it.homepageUrl) }) }
        publish(v)
        // The catalogue half is allowed to fail loudly, and the load turns that into the
        // page's retry: caught here it drew the file as one the catalogue has never seen,
        // because a request had timed out.
        val sha1 = withContext(Dispatchers.IO) { sha1Of(file) }
        val found = withContext(Dispatchers.IO) { modrinth.versionByHash(sha1)?.let { modrinth.resolveProject(it.projectId) } }
        v.project = found
        if (found != null) {
            edit(v) { it.copy(project = found.toDetails()) }
            publish(v)
            v.disclosures = fetchDisclosures(found.id)
            v.creators = fetchCreators(found.id)
        } else if (kind == ContentKind.Mod) {
            // The registry indexes mod jars. A resource or shader pack is never in it.
            val asked = askMirror(sha1)
            v.mirror = asked
            edit(v) { it.copy(mirror = asked?.toView()) }
            // Its releases are folded the way the catalogue's builds are, by the catalogue's version list.
            if (asked != null) {
                val tags = versionTags.get()
                edit(v) { it.copy(gameVersionTags = tags) }
            }
        }
    }

    /** The mirror's answer for a file the catalogue did not know, and no answer when it cannot be asked. */
    private suspend fun askMirror(sha1: String): MirrorMod? {
        val lookup = mirrorLookup ?: return null
        return try {
            withContext(Dispatchers.IO) { lookup.byFile(sha1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Project page: the mirror could not be asked about {}", sha1, e)
            null
        }
    }

    /** The file as it sits, enabled or not. A disabled mod still has a page. */
    private fun locate(instanceDir: Path, kind: ContentKind, fileName: String): Path? {
        val folder = instanceDir.resolve(kind.folderName())
        val enabled = folder.resolve(fileName)
        val disabled = folder.resolve(fileName + DISABLED_SUFFIX)
        return when {
            Files.exists(enabled) -> enabled
            Files.exists(disabled) -> disabled
            else -> null
        }
    }

    /**
     * Disclosures, and a failure here is not the page's failure. An empty list from a
     * refusal would read as "the author declared nothing", so it is logged and the block
     * has nothing to show rather than making a claim.
     */
    private suspend fun fetchDisclosures(projectId: String): List<Disclosure> =
        withContext(Dispatchers.IO) {
            runCatching { modrinth.disclosures(projectId).disclosures.map { it.toView() } }
                .onFailure { if (it is CancellationException) throw it else log.debug("Project page: no disclosures for {}", projectId, it) }
                .getOrDefault(emptyList())
        }

    /** Who is credited, owner first and the rest by role then name. An invitation nobody accepted is not a credit. */
    private suspend fun fetchCreators(projectId: String): List<ProjectCreator> =
        withContext(Dispatchers.IO) {
            runCatching { modrinth.members(projectId) }
                .onFailure { if (it is CancellationException) throw it else log.debug("Project page: no members for {}", projectId, it) }
                .getOrDefault(emptyList())
                .filter { it.accepted }
                .map {
                    ProjectCreator(
                        name = it.user.name?.takeIf { n -> n.isNotBlank() } ?: it.user.username,
                        role = it.role,
                        avatarUrl = it.user.avatarUrl,
                        owner = it.role.equals(OWNER_ROLE, ignoreCase = true),
                    )
                }
                .sortedWith(compareByDescending<ProjectCreator> { it.owner }.thenBy { it.role }.thenBy { it.name })
        }

    /**
     * Hands the rail what it needs. Folding the game versions happens here because it
     * needs the catalogue's release order, and a rail widget that fetched would stop
     * working the moment it is dropped on another surface.
     */
    private suspend fun publish(v: Visit) {
        val view = _view.value?.takeIf { it.visit == v.id } ?: return
        val p = v.project
        val local = v.installed
        val m = v.mirror
        val folded = when {
            // Unfolded rather than unknown when the version list could not be read.
            p != null -> gameVersionChips(p.gameVersions, versionTags.get())
            m != null -> gameVersionChips(m.detail.mcVersions, versionTags.get())
            else -> local?.gameVersions.orEmpty().filter { it.isNotBlank() }.map { GameVersionGroup(it, listOf(it)) }
        }
        open.publish(
            v.id,
            OpenProject(
                targetKey = v.target.key,
                title = view.title,
                slug = p?.slug ?: (v.target as? ProjectTarget.Installed)?.fileName.orEmpty(),
                source = view.source,
                pending = view.loading && p == null && m == null && local == null,
                projectType = p?.projectType,
                packId = v.target.packId,
                gameVersions = folded,
                loaders = p?.loaders
                    ?: m?.detail?.loaders?.filter { it != ANY_LOADER }?.takeIf { it.isNotEmpty() }
                    ?: local?.loaders.orEmpty(),
                categories = p?.let { it.categories + it.additionalCategories } ?: emptyList(),
                clientSide = p?.clientSide ?: m?.detail?.clientSide,
                serverSide = p?.serverSide ?: m?.detail?.serverSide,
                licenseId = p?.license?.id ?: local?.license,
                licenseName = p?.license?.name,
                // Blank is not a date: a stamp that is not there says so rather than
                // printing a label with nothing after it.
                published = p?.published?.takeIf { it.isNotBlank() },
                updated = p?.updated?.takeIf { it.isNotBlank() },
                links = p?.let(::linksOf) ?: m?.links.orEmpty(),
                creators = v.creators,
                disclosures = v.disclosures,
                authors = local?.authors.orEmpty().ifEmpty { m?.authors.orEmpty() },
                dependencies = local?.dependencies.orEmpty().ifEmpty { m?.requires.orEmpty() },
                usedBy = m?.usedBy.orEmpty(),
                // The registry dates nothing, so a page it answers for says nothing about
                // when the project appeared rather than that it is unknown.
                answersPublished = p != null || m == null,
                answersUpdated = p != null || m == null,
                sizeBytes = local?.sizeBytes,
            ),
        )
        // Only an answer is a name. Before anything has spoken the title is the route's own id.
        if (p != null || m != null || local != null) open.name(v.target.key, view.title)
    }

    private fun linksOf(p: ModrinthProject): List<ProjectLink> = buildList {
        p.issuesUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Issues, it)) }
        p.sourceUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Source, it)) }
        p.wikiUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Wiki, it)) }
        p.discordUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Discord, it)) }
        p.donationUrls.forEach { d ->
            d.url.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Donate, it, label = d.platform.takeIf { n -> n.isNotBlank() })) }
        }
    }

    private companion object {
        const val OWNER_ROLE = "Owner"
        const val VANILLA = "vanilla"
        const val ANY_LOADER = "any"

        fun sha1Of(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * One build of a project, resolved for its own page, as [BuildPageView]. Apart from the
 * project page beside it on the same page level: a build is a different subject with a
 * different lifetime, and a person moves between builds without the project changing.
 *
 * It waits for the project page to resolve its project, since the build belongs to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildPageModel(
    private val modrinth: ModrinthClient,
    private val project: ProjectPageModel,
    scope: CoroutineScope,
) {
    private val scope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))

    private val _view = MutableStateFlow<BuildPageView?>(null)
    val view: StateFlow<BuildPageView?> = _view.asStateFlow()

    private var job: Job? = null

    /** Shows the build [versionId] for [visit], once the project page beside it knows its project. */
    fun show(versionId: String, visit: String) {
        scope.launch {
            if (_view.value?.visit == visit && _view.value?.versionId == versionId) return@launch
            _view.value = BuildPageView(versionId = versionId, visit = visit)
            follow()
        }
    }

    /** Reads the build again, once the project page has been read again. */
    fun reload() {
        scope.launch {
            val current = _view.value ?: return@launch
            _view.value = BuildPageView(versionId = current.versionId, visit = current.visit)
            follow()
        }
    }

    /**
     * Loads the build as soon as the project beside it is resolved, and again when the
     * project page is read again. Asking before the project lands is asking with
     * nothing to ask about.
     */
    private fun follow() {
        job?.cancel()
        job = scope.launch {
            var asked: Pair<String?, Boolean>? = null
            project.view.collect { page ->
                val current = _view.value ?: return@collect
                val id = project.projectId
                val key = id to (page?.loading == true)
                if (id == null || page?.loading == true || key == asked) return@collect
                asked = key
                project.loadGameVersionTags()
                load(current.visit, id, current.versionId)
            }
        }
    }

    private fun edit(visit: String, change: (BuildPageView) -> BuildPageView) {
        _view.update { current -> if (current?.visit == visit) change(current) else current }
    }

    private suspend fun load(visit: String, projectId: String, versionId: String) {
        edit(visit) { it.copy(loading = true, failed = false) }
        try {
            val resolved = withContext(Dispatchers.IO) { modrinth.resolveVersion(projectId, versionId) }
            // Published before the names are looked up: naming a dependency is two more
            // round trips each, and a build with a dozen of them held the page back.
            edit(visit) { it.copy(build = resolved.toBuild(), loading = false) }
            val named = namesFor(resolved.dependencies)
            edit(visit) { it.copy(dependencies = named) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Version page: could not resolve {} of {}", versionId, projectId, e)
            edit(visit) { it.copy(failed = true) }
        }
        edit(visit) { it.copy(loading = false) }
    }

    /**
     * Puts a name to each id, and keeps the entry when there is none to put: the author
     * said this build needs something, and dropping the row would quietly unsay it.
     */
    private suspend fun namesFor(raw: List<ModrinthDependency>): List<VersionDependency> =
        withContext(Dispatchers.IO) {
            raw.map { dep ->
                // Cooperative: both calls below swallow what they catch, so without this a
                // cancelled page kept walking the whole list.
                currentCoroutineContext().ensureActive()
                val id = dep.projectId
                val pin = dep.versionId
                val found = id?.let { runCatching { modrinth.resolveProject(it) }.getOrNull() }
                val title = found?.title ?: dep.fileName?.substringBeforeLast('.') ?: id.orEmpty()
                // The pinned build, where there is one. A failure leaves the row saying the shorter, still true thing.
                val pinned = if (id != null && pin != null) runCatching { modrinth.resolveVersion(id, pin).versionNumber }.getOrNull() else null
                VersionDependency(projectId = id, title = title, iconUrl = found?.iconUrl, versionNumber = pinned, kind = kindOf(dep.dependencyType))
            }
        }

    private fun kindOf(type: String): DependencyKind = when (type) {
        "required" -> DependencyKind.Required
        "incompatible" -> DependencyKind.Incompatible
        "embedded" -> DependencyKind.Embedded
        else -> DependencyKind.Optional
    }
}
