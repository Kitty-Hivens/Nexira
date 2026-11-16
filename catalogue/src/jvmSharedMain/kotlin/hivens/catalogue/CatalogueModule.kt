// The catalogue module is compiled into the application and trusted with the host's own
// services, the engine's clients and installers and the contributions the modules brought
// in, which it reads through the host view of each level.
@file:OptIn(HostApi::class)

package hivens.catalogue

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueApi
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.GameVersionGroup
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.PackSources
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectSearchContribution
import hivens.catalogue.api.ProjectSource
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.InstallSnapshot
import hivens.launcher.PackInstallService
import hivens.launcher.imports.LocalPackCreator
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.InstallTargets
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.sources.EngineSources
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelContext
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenContributions
import hivens.widget.api.command
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostService
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The catalogue module as the application carries it: compiled in, and brought in by
 * a document that names [CatalogueApi.ID] like any jar would be.
 */
object CatalogueModule : CompiledModule {
    override val id: String = CatalogueApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(CatalogueModule::class.java, CatalogueApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(CatalogueApi.ID to CatalogueApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(CatalogueApi.ID to setOf(CatalogueApi::class.java.name.substringBeforeLast('.')))

    // The sources: every module, compiled in or a jar, brings its own as one of these.
    override val contributionKinds: List<Class<out ModuleContribution>> = listOf(PackSourceContribution::class.java, ProjectSearchContribution::class.java)
    override val contributions: List<ModuleContribution> = listOf(CatalogueKeysContribution)

    private const val NAME = "catalogue.name"
}

/** The app's work an install runs on, so it outlives the window and the screen that started it. */
class CatalogueWork(val scope: CoroutineScope)

/**
 * Every key in [CatalogueKeys], answered over the sources the modules brought in and the
 * launcher's engine, and the services behind them: the sources, the lists the filters
 * are built from, what searches have shown and what the pages visited were called for
 * the app, what Browse searches, its results, the rail's project and the install that
 * asks where for each window, and a project, a build and a pack page for each page.
 *
 * The sources are the [PackSourceContribution] and [ProjectSearchContribution]
 * contributions the host hands over through [TakenContributions], each opened with the
 * services its own module sees, and handed to the modules the host trusts as
 * [PackSources], the packs module's installs among them. The engine's services are the
 * host's, read through the level's host services, which the host opens to the modules
 * compiled into it. Nothing here carries a secret, so the module's own services are
 * plain ones.
 */
object CatalogueKeysContribution : KeyContribution {
    override val id: String = "catalogue.keys"
    override val label: String = "Catalogue"

    override fun declare(keys: ModuleKeys) {
        // ── App ─────────────────────────────────────────────────────────
        keys.service(ScopeLevel.App) { context -> CatalogueWork(context.coroutineScope) }
        keys.service(ScopeLevel.App) { context ->
            // A host that hands over no contributions has no sources, and the catalogue lists nothing.
            val contributions = context.hostServices.find<TakenContributions>()
            SourceShelf(
                taken = contributions?.all ?: MutableStateFlow(emptyList()),
                servicesOf = context::servicesOf,
                scope = context.coroutineScope,
                compiledIn = contributions?.compiledIn.orEmpty(),
            )
        }
        // The sources as an install reaches them: the ones the shelf took, opened once.
        keys.hostService(ScopeLevel.App) { context -> context.services.get<SourceShelf>() as PackSources }
        keys.service(ScopeLevel.App) { context ->
            val shelf = context.services.get<SourceShelf>()
            CatalogueTagsService(read = { shelf.anySearcher()?.tags() }, scope = context.coroutineScope)
        }
        keys.service(ScopeLevel.App) { context ->
            val shelf = context.services.get<SourceShelf>()
            GameVersionList { shelf.anySearcher()?.gameVersions().orEmpty() }
        }
        keys.service(ScopeLevel.App) { _ -> PackSnapshots() }
        keys.service(ScopeLevel.App) { _ -> ProjectSnapshots() }
        keys.service(ScopeLevel.App) { _ -> PageNames() }

        // ── Window ──────────────────────────────────────────────────────
        keys.service(ScopeLevel.Window) { context ->
            val host = context.hostServices
            BrowseState(
                sources = context.services.get<SourceShelf>().views,
                packs = host.get<IPackRepository>(),
                instancesDir = host.instancesDir(),
                scope = context.coroutineScope,
            )
        }
        keys.service(ScopeLevel.Window) { context ->
            val host = context.hostServices
            val shelf = context.services.get<SourceShelf>()
            SearchService(
                browse = context.services.get<BrowseState>(),
                catalogueFor = shelf::pack,
                projects = ShelfProjects(shelf, host),
                packSnapshots = context.services.get<PackSnapshots>(),
                projectSnapshots = context.services.get<ProjectSnapshots>(),
                scope = context.coroutineScope,
                work = context.services.get<CatalogueWork>().scope,
                sourceGeneration = shelf.generation,
            )
        }
        keys.service(ScopeLevel.Window) { context -> OpenProjects(context.services.get<PageNames>()) }
        keys.service(ScopeLevel.Window) { context ->
            InstallChoices(EngineInstallWork(context.hostServices), context.coroutineScope, context.services.get<CatalogueWork>().scope)
        }

        // ── Page ────────────────────────────────────────────────────────
        keys.service(ScopeLevel.Page) { context ->
            val host = context.hostServices
            val packs = host.get<IPackRepository>()
            ProjectPageModel(
                modrinth = host.get<ModrinthClient>(),
                packOf = { id -> packs.get(id) },
                instancesDir = host.instancesDir(),
                scanner = host.find<InstanceContentScanner>(),
                open = context.services.get<OpenProjects>(),
                versionTags = context.services.get<GameVersionList>(),
                scope = context.coroutineScope,
                work = context.services.get<CatalogueWork>().scope,
                mirrorLookup = host.find<SmrtPackClient>()?.asMirrorLookup(),
                pageInstall = host.find<ModInstaller>()?.let(::InstallerPageInstall),
            )
        }
        keys.service(ScopeLevel.Page) { context ->
            BuildPageModel(context.hostServices.get<ModrinthClient>(), context.services.get<ProjectPageModel>(), context.coroutineScope)
        }
        keys.service(ScopeLevel.Page) { context ->
            val tags = context.services.get<CatalogueTagsService>()
            val shelf = context.services.get<SourceShelf>()
            PackPageModel(
                catalogueFor = shelf::pack,
                snapshots = context.services.get<PackSnapshots>(),
                open = context.services.get<OpenProjects>(),
                tags = tags.tags,
                ensureTags = tags::ensure,
                scope = context.coroutineScope,
                sourceGeneration = shelf.generation,
            )
        }

        // ── Sources ─────────────────────────────────────────────────────
        keys.source(CatalogueKeys.Tags, sample = { CatalogueTags() }) { app -> flowSource(app.services.get<CatalogueTagsService>().tags) }
        keys.source(CatalogueKeys.Sources, sample = { emptyList() }) { app -> flowSource(app.services.get<SourceShelf>().views) }
        keys.source(CatalogueKeys.Browse, sample = { BrowseView() }) { window -> flowSource(window.services.get<BrowseState>().view) }
        keys.source(CatalogueKeys.Results, sample = ::sampleResults) { window -> flowSource(window.services.get<SearchService>().results) }
        keys.source(CatalogueKeys.OpenProject, sample = ::sampleOpenProject) { window -> flowSource(window.services.get<OpenProjects>().open) }
        keys.source(CatalogueKeys.Names, sample = { emptyMap() }) { app -> flowSource(app.services.get<PageNames>().names) }
        keys.source(CatalogueKeys.InstallChoice, sample = { null }) { window -> flowSource(window.services.get<InstallChoices>().view) }
        keys.source(CatalogueKeys.ProjectPage, sample = ::sampleProjectPage) { page -> flowSource(page.services.get<ProjectPageModel>().view) }
        keys.source(CatalogueKeys.BuildPage, sample = { null }) { page -> flowSource(page.services.get<BuildPageModel>().view) }
        keys.source(CatalogueKeys.PackPage, sample = { null }) { page -> flowSource(page.services.get<PackPageModel>().view) }

        // ── Commands ────────────────────────────────────────────────────
        keys.command(CatalogueKeys.LoadTags) { app ->
            val tags = app.services.get<CatalogueTagsService>()
            command { tags.ensure() }
        }
        keys.command(CatalogueKeys.SetKind) { window -> command(window.browse()::setKind) }
        keys.command(CatalogueKeys.SetOrigin) { window -> command(window.browse()::setOrigin) }
        keys.command(CatalogueKeys.SetQuery) { window -> command(window.browse()::setQuery) }
        keys.command(CatalogueKeys.SetSort) { window -> command(window.browse()::setSort) }
        keys.command(CatalogueKeys.SetTarget) { window -> command(window.browse()::setTarget) }
        keys.command(CatalogueKeys.Aim) { window -> command(window.browse()::aimAt) }
        keys.command(CatalogueKeys.ToggleFilter) { window -> command(window.browse()::toggle) }
        keys.command(CatalogueKeys.ExcludeFilter) { window -> command(window.browse()::toggleExclude) }
        keys.command(CatalogueKeys.RemoveFilter) { window -> command(window.browse()::remove) }
        keys.command(CatalogueKeys.ClearFilters) { window -> command(window.browse()::clear) }
        keys.command(CatalogueKeys.SearchFor) { window -> command(window.browse()::searchFor) }
        keys.command(CatalogueKeys.Unlock) { window -> command(window.browse()::unlock) }
        keys.command(CatalogueKeys.Relock) { window -> command(window.browse()::relock) }
        keys.command(CatalogueKeys.SetHideInstalled) { window -> command(window.browse()::setHideInstalled) }
        keys.command(CatalogueKeys.SetSourceOption) { window -> command(window.browse()::setSourceOption) }
        keys.command(CatalogueKeys.ToggleHideInstalled) { window ->
            val browse = window.browse()
            command { browse.toggleHideInstalled() }
        }
        keys.command(CatalogueKeys.ToggleSourceOption) { window -> command(window.browse()::toggleSourceOption) }
        keys.command(CatalogueKeys.MoreResults) { window ->
            val search = window.services.get<SearchService>()
            command { search.more() }
        }
        keys.command(CatalogueKeys.RetryResults) { window ->
            val search = window.services.get<SearchService>()
            command { search.retry() }
        }
        keys.command(CatalogueKeys.ShowResults) { window -> command(window.services.get<SearchService>()::show) }
        keys.command(CatalogueKeys.HideResults) { window -> command(window.services.get<SearchService>()::hide) }
        keys.command(CatalogueKeys.ScrollResults) { window -> command(window.services.get<SearchService>()::scroll) }
        keys.command(CatalogueKeys.InstallResult) { window -> command(window.services.get<SearchService>()::install) }
        keys.command(CatalogueKeys.ChooseInstall) { window -> command(window.services.get<InstallChoices>()::choose) }
        keys.command(CatalogueKeys.InstallInto) { window -> command(window.services.get<InstallChoices>()::installInto) }
        keys.command(CatalogueKeys.InstallNewPack) { window -> command(window.services.get<InstallChoices>()::installNewPack) }
        keys.command(CatalogueKeys.RetryChoice) { window ->
            val choices = window.services.get<InstallChoices>()
            command { choices.retry() }
        }
        keys.command(CatalogueKeys.DismissInstall) { window ->
            val choices = window.services.get<InstallChoices>()
            command { choices.dismiss() }
        }
        keys.command(CatalogueKeys.ShowProject) { page ->
            val model = page.services.get<ProjectPageModel>()
            command { visit -> model.show(visit.target, visit.visit) }
        }
        keys.command(CatalogueKeys.HideProject) { page -> command(page.services.get<ProjectPageModel>()::hide) }
        keys.command(CatalogueKeys.ReloadProject) { page ->
            val model = page.services.get<ProjectPageModel>()
            val build = page.services.get<BuildPageModel>()
            command {
                model.reload()
                build.reload()
            }
        }
        keys.command(CatalogueKeys.LoadVersions) { page ->
            val model = page.services.get<ProjectPageModel>()
            command { model.loadVersions() }
        }
        keys.command(CatalogueKeys.RetryVersions) { page ->
            val model = page.services.get<ProjectPageModel>()
            command { model.retryVersions() }
        }
        keys.command(CatalogueKeys.InstallProject) { page ->
            val model = page.services.get<ProjectPageModel>()
            command { install -> model.install(install.versionId) }
        }
        keys.command(CatalogueKeys.RetryInstall) { page ->
            val model = page.services.get<ProjectPageModel>()
            command { model.retryInstall() }
        }
        keys.command(CatalogueKeys.ShowBuild) { page ->
            val build = page.services.get<BuildPageModel>()
            command { visit -> build.show(visit.versionId, visit.visit) }
        }
        keys.command(CatalogueKeys.ShowPack) { page ->
            val model = page.services.get<PackPageModel>()
            command { visit -> model.show(visit.origin, visit.packId, visit.visit) }
        }
        keys.command(CatalogueKeys.HidePack) { page -> command(page.services.get<PackPageModel>()::hide) }
        keys.command(CatalogueKeys.RetryPack) { page ->
            val model = page.services.get<PackPageModel>()
            command { model.retry() }
        }
    }
}

private fun LevelContext.browse(): BrowseState = services.get<BrowseState>()

/** The folder every pack's folder sits in. */
private fun LevelServices.instancesDir(): Path = get<PlatformPaths>().dataDir.resolve(INSTANCES_DIR)

private const val INSTANCES_DIR = "instances"

private val log = LoggerFactory.getLogger("hivens.catalogue.Module")

/**
 * The one project source whose projects the engine installs, by id, until installs move
 * into the sources. A project another source found is not installed through it: its id
 * would be looked up at a source that never listed it.
 */
private val ENGINE_INSTALLS: String = EngineSources.Modrinth.id

/**
 * A project search as the window's search asks for one: the search from the project
 * source that searches the type, and the install of a result into the target pack and
 * what that pack holds from the engine, which still installs projects itself, from the
 * one source it knows.
 */
internal class ShelfProjects(private val shelf: SourceShelf, private val host: LevelServices) : ProjectCatalogue {
    override fun sourceOf(type: String): String? = shelf.searcher(type)?.id

    override suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit> {
        val source = shelf.searcher(type)
        if (source == null) {
            log.info("browse: no module brings a source that searches '{}'", type)
            return emptyList()
        }
        return source.searcher.search(type, query, filters, offset, limit, sort)
    }

    override suspend fun installNewest(type: String, projectId: String, pack: ContentDestination.Pack): ModInstaller.Outcome? {
        val source = sourceOf(type)
        if (source != ENGINE_INSTALLS) throw SourceCannotInstall(source)
        return host.get<ModrinthClient>().newestMatchingVersion(projectId, pack.mcVersion, pack.loader)
            ?.let { host.get<ModInstaller>().install(pack.dir, it, pack.mcVersion, pack.loader) }
    }

    override suspend fun presentProjects(dir: Path): Set<String> = host.get<ModInstaller>().presentProjects(dir)
}

/** The engine as the install that asks where needs it. */
internal class EngineInstallWork(private val host: LevelServices) : InstallWork {
    private val modrinth get() = host.get<ModrinthClient>()
    private val installService get() = host.get<PackInstallService>()

    override suspend fun listVersions(projectId: String): List<ModrinthVersion> = modrinth.listVersions(projectId)

    override suspend fun candidates(projectId: String, listing: List<ModrinthVersion>): List<InstallTargets.Candidate> =
        host.get<InstallTargets>().forProject(projectId, listing)

    override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome =
        host.get<ModInstaller>().install(dir, version, mc, loader)

    override val installs: StateFlow<Map<String, InstallSnapshot>> get() = installService.installs

    override fun createPack(key: String, name: String, mcVersion: String, loader: String?) {
        val creator = host.get<LocalPackCreator>()
        val _ = installService.run(key = key, title = name) { reserve, progress -> creator.create(name, mcVersion, loader, "", reserve, progress) }
    }

    override fun dismiss(key: String) = installService.dismiss(key)

    override suspend fun packOf(id: String): PackInstance? = host.get<IPackRepository>().get(id)

    override val instancesDir: Path get() = host.instancesDir()

    override fun installsFrom(source: String?): Boolean = source == null || source == ENGINE_INSTALLS
}

/** What the palette shows the results on: one project, found. */
private fun sampleResults(): SearchResults = SearchResults(
    status = SearchStatus.Loaded,
    projects = listOf(ProjectHit(projectId = "sample", title = "Sample Project", description = "A project as a search shows one.", author = "Author", downloads = 1_250_000, follows = 4_200)),
)

/** What the palette shows the rail's project blocks on. Invented, since a real project's people are not ours to show. */
private fun sampleOpenProject(): OpenProject = OpenProject(
    targetKey = "preview:project",
    title = "Sample Mod",
    slug = "sample-mod",
    source = ProjectSource.Catalogue,
    gameVersions = listOf(GameVersionGroup("1.21.1", listOf("1.21.1"))),
    loaders = listOf("fabric", "neoforge"),
    categories = listOf("technology", "utility"),
    links = listOf(ProjectLink(ProjectLinkKind.Source, "https://example.invalid/source")),
)

/** A project page to show the page's widgets on where there is no project. Display data only. */
private fun sampleProjectPage(): ProjectPageView = ProjectPageView(
    target = ProjectTarget.Catalogue("sample"),
    visit = "sample",
    loading = false,
)
