package hivens.ui.screens.mod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.AppStrings
import hivens.ui.components.relativeAge
import hivens.launcher.instance.DISABLED_SUFFIX
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.ModInstaller
import hivens.launcher.instance.folderName
import hivens.launcher.instance.loadersFor
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Everything the project page has to find out, and the one thing it publishes.
 *
 * The page is addressed by a [ModTarget] and resolves the rest here, so the back
 * stack stays small and a revisit re-reads rather than replaying a stale copy.
 *
 * The two targets converge deliberately. A catalogue entry is fetched by id; a
 * file is hashed and looked up, and when the catalogue does not know the hash the
 * page keeps its shape and draws what the archive itself declared. Nothing about
 * the layout changes between the two, because the questions a reader asks do not
 * change with where the answer happens to live.
 */
class ModDetailState(
    val target: ModTarget,
    private val modrinth: ModrinthClient,
    private val repo: IPackRepository,
    private val dataDir: Path,
    private val scanner: InstanceContentScanner,
    private val open: OpenProjectState,
    /**
     * The reader's language, for the two facts the rail receives already written:
     * how long ago a build was published and when it was updated. The rail draws
     * what it is handed, so whoever hands it has to speak.
     */
    private val strings: AppStrings,
    // Composed from the two the state already holds, so a caller that has those
    // has this. Koin binds the same pair.
    private val installer: ModInstaller = ModInstaller(modrinth, scanner),
) {
    /**
     * Written by [load] and read by the page.
     *
     * Internal rather than private so a render sheet can stand a page up from
     * fixtures. The alternative was a page that can only be looked at by running
     * the app against the live catalogue, which is how a layout defect ships: the
     * one thing that finds them is a picture, and a picture needs a way in.
     */
    var loading by mutableStateOf(true)
        internal set

    /** The lookup itself did not run. Never the same as "the catalogue has no entry". */
    var failed by mutableStateOf(false)
        internal set

    var project by mutableStateOf<ModrinthProject?>(null)
        internal set

    var disclosures by mutableStateOf<List<ModrinthDisclosure>>(emptyList())
        internal set

    var creators by mutableStateOf<List<ProjectCreator>>(emptyList())
        internal set

    /** What the page can do about putting this project into a pack. */
    var install by mutableStateOf<InstallAction>(InstallAction.None)
        internal set

    /** True while a download is in flight, so the button can say so. */
    var installing by mutableStateOf(false)
        private set

    /**
     * The last install did not land.
     *
     * Kept rather than logged, because silence after a click reads as success and
     * that is the exact failure the mod browser had before its own state was
     * split out: a row that said Installed for a file that never arrived.
     */
    var installFailed by mutableStateOf(false)
        private set

    /**
     * Required dependencies with no build for this pack.
     *
     * The one outcome that has to be shown rather than swallowed: the mod is in
     * and will not run, and nothing else on the page would ever say so.
     */
    var installMissing by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * The project exists and has nothing that runs on this pack.
     *
     * Kept apart from [installFailed], which means the attempt broke. This one is
     * an ANSWER: there is no such build, retrying will find no such build, and the
     * only thing left to do is open the versions tab and choose deliberately. The
     * two used to be one flag, so a project that simply does not support the pack
     * offered a Retry that could never succeed.
     */
    var installNoBuild by mutableStateOf(false)
        private set

    /**
     * Every build the catalogue has, or null until the versions tab asks.
     *
     * Not fetched with the page. A reader who came to read the description never
     * needs it, and the list is the largest single response a project has.
     */
    var versions by mutableStateOf<List<ModrinthVersion>?>(null)
        internal set

    /** The listing itself did not run. Never the same as a project with no builds. */
    var versionsFailed by mutableStateOf(false)
        internal set

    /**
     * True while the build list is in flight.
     *
     * Kept apart from "versions is null", which was the only signal and meant three
     * different things at once: the fetch is running, the fetch never started
     * because the page had not resolved its project yet, and there is no project to
     * ask about. The pane drew a spinner for all three, so a local jar span forever
     * and a catalogue mod span or not depending on whether the reader reached the
     * tab before the page finished loading.
     */
    var versionsLoading by mutableStateOf(false)
        internal set

    /** The archive on disk, for a page opened on an installed file. */
    var installed by mutableStateOf<InstalledContent?>(null)
        internal set

    val source: ProjectSource
        get() = if (project != null) ProjectSource.Catalogue else ProjectSource.Local

    /** The title the header shows, from whichever side can answer. */
    val title: String
        get() = project?.title
            ?: installed?.displayName
            ?: when (val t = target) {
                is ModTarget.Catalogue -> t.projectId
                is ModTarget.Installed -> t.fileName
            }

    val description: String?
        get() = project?.description?.takeIf { it.isNotBlank() } ?: installed?.description

    val body: String?
        get() = project?.body?.takeIf { it.isNotBlank() }

    suspend fun load() {
        loading = true
        failed = false
        // Published before the fetch, not after it. The rail's widgets draw nothing
        // for a null project, so until the first answer landed the whole panel
        // collapsed and the page opened looking like it had no rail at all -- then
        // one appeared a second later. The page knows its own name from the route,
        // so the blocks get their shape on the first frame and fill in.
        publish()
        try {
            when (val t = target) {
                is ModTarget.Catalogue -> loadCatalogue(t.projectId)
                is ModTarget.Installed -> loadInstalled(t)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A page that failed to load and a project that declares nothing must
            // not look the same, so the failure is kept rather than folded into an
            // empty result.
            log.warn("Project page: could not resolve {}", target.key, e)
            failed = true
        } finally {
            // Restored even when the caller walked away mid-flight. A retry
            // launched from one of the panes runs on the PANE's scope, so
            // switching tabs while it was in flight left this true with nothing
            // left to clear it: all three panes then drew a spinner, with no
            // retry and no effect whose key had changed to run again.
            loading = false
        }
        publish()
    }

    /**
     * Takes the rail's project view back down.
     *
     * Called on the way out. A rail still describing a project the reader has left
     * is worse than an empty one, and the widgets themselves cannot know: they see
     * a value, not a navigation.
     */
    fun clear() {
        open.clearIf(target.key)
    }

    private suspend fun loadCatalogue(projectId: String) {
        project = withContext(Dispatchers.IO) { modrinth.resolveProject(projectId) }
        disclosures = fetchDisclosures(projectId)
        creators = fetchCreators(projectId)
        install = resolveInstall(projectId)
    }

    /**
     * Whether this project can be put into the pack the reader came from, and
     * whether it is already there.
     *
     * Asked of the folder rather than remembered from a click. A pack of ninety
     * mods reached through its own browser has most of the catalogue already, and
     * a page offering to install what is installed is a wasted click that ends in
     * a no-op download.
     */
    private suspend fun resolveInstall(projectId: String): InstallAction {
        val pack = resolveDestination() ?: return InstallAction.None
        val present = withContext(Dispatchers.IO) {
            runCatching { installer.presentProjects(pack.dir) }
                .onFailure { log.debug("Project page: could not read what {} holds", pack.name, it) }
                .getOrDefault(emptySet())
        }
        return if (projectId in present) InstallAction.Present(pack.name) else InstallAction.Install(pack.name)
    }

    /**
     * The pack this page can act on, resolved once and kept.
     *
     * Both the install button and the versions tab need the same four facts about
     * it, and reading the record twice is how the two end up disagreeing about
     * which loader the pack runs.
     */
    private suspend fun resolveDestination(): Destination? {
        destination?.let { return it }
        val id = when (val t = target) {
            is ModTarget.Catalogue -> t.intoInstanceId
            is ModTarget.Installed -> t.instanceId
        } ?: return null
        val pack = repo.get(id) ?: return null
        val resolved = Destination(
            name = pack.displayName,
            dir = dataDir.resolve(INSTANCES_DIR).resolve(pack.instanceDirName),
            mc = pack.cachedManifest?.minecraftVersion.orEmpty(),
            loader = pack.cachedManifest?.loaderName
                ?.takeIf { it.isNotBlank() && !it.equals(VANILLA, ignoreCase = true) }
                ?.lowercase()
                .orEmpty(),
        )
        destination = resolved
        return resolved
    }

    /** Where the page can put things, and what runs there. */
    internal class Destination(val name: String, val dir: Path, val mc: String, val loader: String)

    /**
     * Internal rather than private for the same reason the loaded fields are: a
     * render sheet has to be able to stand up a page that knows which pack it is
     * aimed at, or the compatibility marking can only be looked at by running the
     * app against a real instance.
     */
    internal var destination: Destination? = null

    /**
     * The catalogue's version list, for folding a build's game versions into
     * ranges the way the project's own block does. Empty until something asks.
     */
    var gameVersionTags by mutableStateOf<List<ModrinthGameVersion>>(emptyList())
        internal set

    /**
     * Asks for the folding order without asking for the build list.
     *
     * A single build's page folds its game versions the same way the table does
     * and has no use for the other four hundred builds, so it takes this instead
     * of [loadVersions] -- which would fetch the largest response the catalogue
     * has to serve a page that shows one row of chips.
     */
    suspend fun loadGameVersionTags() {
        if (gameVersionTags.isEmpty()) gameVersionTags = versionTags()
    }

    /** The pack's runtime, for marking which builds can actually run. */
    val packMcVersion: String get() = destination?.mc.orEmpty()
    val packLoaders: List<String> get() = destination?.loader
        ?.let { loadersFor(ContentKind.Mod, it) }
        .orEmpty()

    /**
     * Fetches the build list, once per visit.
     *
     * Called when the versions tab is first opened rather than on arrival: a
     * reader who came for the description never pays for it.
     */
    suspend fun loadVersions() {
        // No project means nothing to ask: either the page is still resolving one,
        // in which case the caller runs this again when it arrives, or the
        // catalogue has never seen this file and there is no list to get.
        val projectId = project?.id ?: return
        if (versions != null || versionsLoading) return
        versionsLoading = true
        versionsFailed = false
        try {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { modrinth.listVersions(projectId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        log.warn("Project page: listing versions of {} failed", projectId, it)
                    }
                    .getOrNull()
            }
            if (fetched == null) versionsFailed = true else versions = fetched
            // The table folds each build's game versions, and folding needs the
            // catalogue's release order.
            if (gameVersionTags.isEmpty()) gameVersionTags = versionTags()
        } finally {
            versionsLoading = false
        }
    }

    /**
     * Whether an install could ever be offered here, answerable before anything
     * loads.
     *
     * The route carries the pack; only what is already in that pack needs reading.
     * Keeping the two apart is what lets the header reserve the action's place from
     * the first frame instead of growing one under the reader's hands.
     */
    val installPossible: Boolean
        get() = (target as? ModTarget.Catalogue)?.intoInstanceId != null

    /** Whether the catalogue has an entry at all, once the page has finished looking. */
    val knownToCatalogue: Boolean get() = project != null

    /**
     * Installs one named build rather than the newest that fits.
     *
     * The tab exists so a reader can choose, so this deliberately does not
     * second-guess the choice: a build marked incompatible is still installable,
     * because a pack the catalogue thinks is 1.20.1 may well run a 1.20 mod and
     * the reader is the one looking at both.
     */
    suspend fun installVersion(version: ModrinthVersion) {
        if (installing) return
        val pack = resolveDestination() ?: return
        installing = true
        installFailed = false
        installNoBuild = false
        installMissing = emptyList()
        try {
            val outcome = withContext(Dispatchers.IO) {
                installer.install(pack.dir, version, pack.mc, pack.loader)
            }
            if (outcome.ok) {
                install = InstallAction.Present(pack.name)
                installMissing = outcome.missing
            } else {
                installFailed = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing build {} from the project page failed", version.id, e)
            installFailed = true
        } finally {
            installing = false
        }
    }

    /**
     * Downloads the best build for the pack this page was opened from.
     *
     * "Best" is the newest that runs on the pack's game version and loader, which
     * is the same rule the mod browser installs by. A project with no such build
     * is not a failure: it exists and does not support this pack, and the two read
     * differently on the button.
     */
    suspend fun installIntoPack() {
        val action = install
        if (action !is InstallAction.Install || installing) return
        val projectId = project?.id ?: return

        installing = true
        installFailed = false
        installNoBuild = false
        installMissing = emptyList()
        try {
            val pack = resolveDestination() ?: return
            val version = withContext(Dispatchers.IO) {
                modrinth.newestMatchingVersion(projectId, pack.mc, pack.loader)
            }
            if (version == null) {
                // Refused rather than substituted. The pick is the launcher's, so
                // it does not get to quietly choose a build for another loader; the
                // reader can still take one by name from the versions tab.
                log.info("Project {} has no build for {} / {}", projectId, pack.mc, pack.loader)
                installNoBuild = true
                return
            }
            val outcome = withContext(Dispatchers.IO) {
                installer.install(pack.dir, version, pack.mc, pack.loader)
            }
            if (outcome.ok) {
                install = InstallAction.Present(pack.name)
                installMissing = outcome.missing
            } else {
                installFailed = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing {} from the project page failed", projectId, e)
            installFailed = true
        } finally {
            installing = false
        }
    }

    private suspend fun loadInstalled(t: ModTarget.Installed) {
        val pack = repo.get(t.instanceId)
        val dir = pack?.let { dataDir.resolve(INSTANCES_DIR).resolve(it.instanceDirName) }
        val file = dir?.let { locate(it, t) }

        if (file != null) {
            installed = withContext(Dispatchers.IO) { runCatching { scanner.read(file, t.kind) }.getOrNull() }
            // The catalogue half is allowed to fail loudly, and [load] turns that
            // into the page's retry. Caught here it produced the worst reading the
            // page has: a file drawn as one the catalogue has never seen, with a
            // versions tab saying so, because a request had timed out.
            val found = withContext(Dispatchers.IO) {
                modrinth.versionByHash(sha1Of(file))?.let { modrinth.resolveProject(it.projectId) }
            }
            project = found
            if (found != null) {
                disclosures = fetchDisclosures(found.id)
                creators = fetchCreators(found.id)
            }
        }
    }

    /** The file as it sits, enabled or not. A disabled mod still has a page. */
    private fun locate(instanceDir: Path, t: ModTarget.Installed): Path? {
        val folder = instanceDir.resolve(t.kind.folderName())
        val enabled = folder.resolve(t.fileName)
        val disabled = folder.resolve(t.fileName + DISABLED_SUFFIX)
        return when {
            Files.exists(enabled) -> enabled
            Files.exists(disabled) -> disabled
            else -> null
        }
    }

    /**
     * Disclosures, and a failure here is not the page's failure.
     *
     * The route is the client's only v3 call and it can be absent for a project
     * the older route still serves. An empty list from a refusal reads as "the
     * author declared nothing", which is wrong, so a refusal is logged and the
     * block simply has nothing to show rather than making a claim.
     */
    private suspend fun fetchDisclosures(projectId: String): List<ModrinthDisclosure> =
        withContext(Dispatchers.IO) {
            runCatching { modrinth.disclosures(projectId).disclosures }
                .onFailure { log.debug("Project page: no disclosures for {}", projectId, it) }
                .getOrDefault(emptyList())
        }

    /**
     * Who is credited, owner first and the rest by role then name.
     *
     * An invitation nobody accepted is not a credit, so it is left out. A refusal
     * leaves the block empty rather than failing the page: not knowing who wrote
     * something is a smaller loss than not showing it at all.
     */
    private suspend fun fetchCreators(projectId: String): List<ProjectCreator> =
        withContext(Dispatchers.IO) {
            runCatching { modrinth.members(projectId) }
                .onFailure { log.debug("Project page: no members for {}", projectId, it) }
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
     * Hands the rail what it needs, once, when the answer has settled.
     *
     * Folding the game versions happens here rather than in the rail because it
     * needs the catalogue's release order, and a rail widget that fetched would be
     * a rail widget that stops working the moment it is dropped on another
     * surface.
     */
    private suspend fun publish() {
        val p = project
        val local = installed
        // A jar names the version it was built against at best, and the range it
        // also runs on is not in the archive at all. An empty list is the honest
        // answer and the rail draws the question mark.
        // The catalogue's answer when there is one, and the archive's own when there
        // is not. A jar names the loader it needs and usually the game version it
        // was built against, and saying "unknown" over the top of that is throwing
        // away a fact the file had already handed us.
        val folded = when {
            p != null -> foldGameVersions(p.gameVersions, versionTags())
            else -> local?.gameVersions.orEmpty().filter { it.isNotBlank() }
        }
        open.publish(
            OpenProject(
                targetKey = target.key,
                title = title,
                slug = p?.slug ?: (target as? ModTarget.Installed)?.fileName.orEmpty(),
                source = source,
                gameVersionLabels = folded,
                loaders = p?.loaders ?: local?.loaders.orEmpty(),
                categories = p?.let { it.categories + it.additionalCategories } ?: emptyList(),
                clientSide = p?.clientSide,
                serverSide = p?.serverSide,
                licenseId = p?.license?.id ?: local?.license,
                licenseName = p?.license?.name,
                // Formatted here, because the rail renders what it is given and a
                // raw ISO stamp is not something a reader was ever meant to see.
                // Both halves: how long ago for reading, the exact moment for the
                // tooltip behind it.
                // Blank is not a date. relativeAge answers "" for a stamp that is not
                // there, and handing that through printed "Published" with nothing
                // after it rather than saying the date is not known.
                publishedAt = relativeAge(p?.published, strings).takeIf { it.isNotBlank() },
                publishedExact = formatBuildTimestamp(p?.published),
                updatedAt = relativeAge(p?.updated, strings).takeIf { it.isNotBlank() },
                updatedExact = formatBuildTimestamp(p?.updated),
                links = p?.let(::linksOf) ?: emptyList(),
                creators = creators,
                disclosures = disclosures,
                authors = local?.authors.orEmpty(),
                dependencies = local?.dependencies.orEmpty(),
                sizeBytes = local?.sizeBytes,
            ),
        )
    }

    private fun linksOf(p: ModrinthProject): List<ProjectLink> = buildList {
        p.issuesUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Issues, it)) }
        p.sourceUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Source, it)) }
        p.wikiUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Wiki, it)) }
        p.discordUrl?.takeIf { it.isNotBlank() }?.let { add(ProjectLink(ProjectLinkKind.Discord, it)) }
        p.donationUrls.forEach { d ->
            d.url.takeIf { it.isNotBlank() }?.let {
                add(ProjectLink(ProjectLinkKind.Donate, it, label = d.platform.takeIf { n -> n.isNotBlank() }))
            }
        }
    }

    /**
     * The catalogue's whole version list, newest first, asked once per process.
     *
     * All of it and not just the releases: folding needs to know which entries are
     * snapshots and which are the old alpha and beta lines, and it needs the dates
     * to tell a forward-looking snapshot from one already covered by a release.
     *
     * It is the same for every project and changes when Mojang ships, so a page
     * that fetched it per visit would ask the same question of the same server
     * every time a reader opened a mod.
     */
    private suspend fun versionTags(): List<ModrinthGameVersion> {
        cachedTags?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching { modrinth.gameVersions() }
                .onFailure { log.debug("Project page: game-version tags unavailable", it) }
                .getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) cachedTags = fetched
        return fetched
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ModDetailState::class.java)
        private const val INSTANCES_DIR = "instances"
        private const val OWNER_ROLE = "Owner"
        private const val VANILLA = "vanilla"

        /**
         * Process-wide, because the answer belongs to the catalogue rather than to
         * any one page, and every page folds against the same list.
         */
        @Volatile
        private var cachedTags: List<ModrinthGameVersion>? = null

        private fun sha1Of(file: Path): String {
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
