package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CatalogueLinkKind
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.data.PackOrigin
import hivens.launcher.InstallPhase
import hivens.launcher.PackInstallService
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.ImageGallery
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.galleryMedia
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.components.relativeAge
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTabRow
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.render.MarkdownHtml
import hivens.ui.feature.catalogue.project.BuildsTable
import hivens.ui.feature.catalogue.project.ChangelogList
import hivens.ui.feature.catalogue.project.HeaderStat
import hivens.ui.feature.catalogue.project.OpenProject
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.ui.feature.catalogue.project.ProjectCreator
import hivens.ui.feature.catalogue.project.ProjectHeader
import hivens.ui.feature.catalogue.project.ProjectLink
import hivens.ui.feature.catalogue.project.ProjectLinkKind
import hivens.ui.feature.catalogue.project.ProjectSource
import hivens.ui.feature.catalogue.project.compactCount
import hivens.ui.feature.catalogue.project.gameVersionChips
import hivens.ui.feature.catalogue.project.rememberLinkFollower
import hivens.ui.feature.catalogue.project.toBuild
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.datatransfer.StringSelection

/**
 * A pack's page in the catalogue, whichever source lists it.
 *
 * Built from the project page's own parts, because a reader moving between a mod
 * and the pack it sits in should not meet two pages: the same header, the same
 * tabs, the same table of builds and the same changelog, and the facts in the
 * right rail's project view, which reads what this page publishes. A source fills
 * what it has. The catalogue counts downloads and credits a team; the mirror names
 * the runtime, the sign-in and the mods a build holds; what neither says is left
 * out rather than shown empty.
 *
 * The install runs on [PackInstallService]'s app scope, so progress is read back
 * from the service rather than owned here: leaving this screen does not cancel
 * the download, and coming back re-attaches to it.
 */
@Composable
fun CataloguePackDetailScreen(
    origin: PackOrigin,
    packId: String,
    onBack: () -> Unit,
    onInstalled: (instanceId: String) -> Unit,
) {
    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val installService: PackInstallService = koinInject()
    val session: BrowseSession = koinInject()
    val openProject: OpenProjectState = koinInject()
    val browseTags: BrowseTags = koinInject()
    val authProviders: AuthProviderRegistry = koinInject()
    val families = LocalSurfaceFamilies.current

    PuppetScreen("CataloguePack.$packId")
    // Back is the top-bar breadcrumb's job, but automation still needs a handle on it.
    PuppetClick("catalogue.detail.back") { onBack() }

    // Opens on the page as it was last read, not on a spinner. The details are the
    // same on the way back as they were on the way in, so rebuilding them from
    // nothing meant the page a reader had just closed came back empty and filled in
    // again in front of them.
    var state by remember(origin, packId) {
        mutableStateOf<DetailState>(
            session.details(origin, packId)?.let { DetailState.Loaded(it) } ?: DetailState.Loading,
        )
    }
    var retryTick by remember(origin, packId) { mutableIntStateOf(0) }

    // Install state is owned by the app-scoped service, not this composition.
    // Match on (origin, packId) so a return to this screen re-attaches to an
    // install started before we navigated away.
    val installs by installService.installs.collectAsState()
    val active = installs.values.firstOrNull { it.origin == origin && it.packId == packId }
    val installingVersion = (active?.phase as? InstallPhase.Running)?.let { active.versionId }
    val installError = (active?.phase as? InstallPhase.Failed)?.message

    // Success navigates to the installed instance, then evicts the terminal
    // snapshot so a later reinstall starts clean.
    LaunchedEffect(active?.key, active?.phase) {
        val snap = active
        val phase = snap?.phase
        if (snap != null && phase is InstallPhase.Succeeded) {
            installService.dismiss(snap.key)
            onInstalled(phase.instanceId)
        }
    }

    fun install(details: CataloguePackDetails, version: CataloguePackVersion) {
        installService.start(
            pack = CataloguePack(
                origin    = details.origin,
                id        = details.id,
                title     = details.title,
                tagline   = details.tagline,
                iconUrl   = details.iconUrl,
                bannerUrl = details.bannerUrl,
            ),
            version = version,
        )
    }

    LaunchedEffect(origin, packId, retryTick) {
        // Only a page with nothing on it says so. A refresh behind a page already
        // read replaces it when it lands, the way the catalogue list does.
        if (state !is DetailState.Loaded) state = DetailState.Loading
        val catalogue = registry.forOrigin(origin)
            ?: return@LaunchedEffect run { state = DetailState.Error(s.browseDetailErrorMessage) }
        try {
            // Every answer the source gives, the stored one and the fresh one behind
            // it. Read once, the page and its Install offered whatever the cache held.
            catalogue.detailsStream(packId).flowOn(Dispatchers.IO).collect { details ->
                session.putDetails(origin, packId, details)
                state = DetailState.Loaded(details)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A source that failed while a page of its own is on screen keeps
            // showing it: an error page loses more than the error explains.
            if (state !is DetailState.Loaded) state = DetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    // The rail follows the page for exactly as long as the page is mounted, the
    // way the project page holds it, and reads what the page publishes.
    val targetKey = catalogueTargetKey(origin, packId)
    // This visit, as the rail's owner: a page arriving over this one takes the rail
    // and what this one still publishes on its way out is dropped.
    val railOwner = remember(targetKey) { Any() }
    DisposableEffect(railOwner) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW, railOwner)
        openProject.claim(railOwner)
        onDispose {
            families.reset(RIGHT_RAIL_SURFACE, railOwner)
            openProject.release(railOwner)
        }
    }
    val tags by browseTags.tags.collectAsState()
    LaunchedEffect(Unit) { browseTags.ensure() }
    LaunchedEffect(state, tags, s) {
        val current = state
        val loaded = (current as? DetailState.Loaded)?.details
        openProject.publish(
            railOwner,
            when {
                loaded != null -> openPackOf(targetKey, loaded, tags?.gameVersions.orEmpty(), s) { key -> authProviders[key]?.displayName ?: key }
                // A page that could not be read has no facts to wait for. Pending
                // here left the rail's blocks empty for as long as the page stayed.
                current is DetailState.Error -> null
                else -> OpenProject(targetKey = targetKey, title = packId, slug = packId, source = ProjectSource.Catalogue, pending = true)
            },
        )
        loaded?.let { openProject.name(targetKey, it.title) }
    }

    val tabState = rememberSaveable(origin, packId, stateSaver = Saver(save = { it.name }, restore = { PackTab.valueOf(it) })) {
        mutableStateOf(PackTab.Description)
    }
    var tab by tabState
    val loaded = (state as? DetailState.Loaded)?.details
    val tabs = remember(loaded) { loaded?.let(::tabsOf) ?: listOf(PackTab.Description) }
    LaunchedEffect(tabs) { if (tab !in tabs) tab = PackTab.Description }
    val page = CataloguePackPage(
        packId = packId,
        detail = state,
        tab = tabState,
        tabs = tabs,
        gameVersionTags = tags?.gameVersions.orEmpty(),
        installingVersion = installingVersion,
        installError = installError,
        onInstall = ::install,
        onRetry = { retryTick++ },
    )

    // A widget surface, the way the project page is: the header and the tabs in
    // `header`, the tab's pane in `body`, each slot on the panel it always sat on.
    CompositionLocalProvider(LocalCataloguePackPage provides page) {
        Column(Modifier.fillMaxSize()) {
            NxSurface(
                kind = SurfaceKind.Panel,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp),
            ) {
                SlotRenderer(
                    SurfaceId(CATALOGUE_PACK_SURFACE),
                    SlotId("header"),
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
            NxSurface(
                kind = SurfaceKind.Panel,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            ) {
                SlotRenderer(SurfaceId(CATALOGUE_PACK_SURFACE), SlotId("body"), Modifier.fillMaxSize())
            }
        }
    }
    tabs.forEach { t -> PuppetClick("catalogue.detail.tab.${t.name.lowercase()}") { tab = t } }

    loaded?.let { d ->
        PuppetClick("catalogue.detail.install", enabled = installingVersion == null && d.versions.isNotEmpty()) {
            latestOf(d)?.let { install(d, it) }
        }
    }
}

/** What a catalogue pack's page is known by to the rail and the trail. */
internal fun catalogueTargetKey(origin: PackOrigin, packId: String): String = "pack:${origin.name}:$packId"

/** The catalogue pack page's surface, see [CataloguePackPage]. */
internal const val CATALOGUE_PACK_SURFACE = "catalogue.pack"

/**
 * What the catalogue pack page's widgets share: the page as read, the tab it
 * shows, and the install it offers. Per visit, for the reason the project page's
 * [hivens.ui.feature.catalogue.project.ProjectPage] is.
 */
internal class CataloguePackPage(
    val packId: String,
    val detail: DetailState,
    tab: MutableState<PackTab>,
    /** The tabs the pack has something behind. */
    val tabs: List<PackTab>,
    val gameVersionTags: List<ModrinthGameVersion>,
    /** The build being installed, or null. */
    val installingVersion: String?,
    val installError: String?,
    val onInstall: (CataloguePackDetails, CataloguePackVersion) -> Unit,
    /** Reads the pack again after a read that failed. */
    val onRetry: () -> Unit,
) {
    var tab: PackTab by tab

    val details: CataloguePackDetails? get() = (detail as? DetailState.Loaded)?.details
}

/** The catalogue pack page the widgets are drawn for, or null on any other surface. */
internal val LocalCataloguePackPage: ProvidableCompositionLocal<CataloguePackPage?> = compositionLocalOf { null }

/** The page's header: the pack's mark, name, line, counts and tags, and its install. */
@Composable
internal fun CataloguePackPageHeader(page: CataloguePackPage) {
    PackHeader(
        packId = page.packId,
        details = page.details,
        installingVersion = page.installingVersion,
        installError = page.installError,
        onInstall = page.onInstall,
    )
}

@Composable
internal fun CataloguePackPageTabs(page: CataloguePackPage) {
    val s = LocalStrings.current
    NxTabRow(
        tabs = page.tabs.map { it.label(s) },
        selected = page.tabs.indexOf(page.tab).coerceAtLeast(0),
        onSelect = { page.tab = page.tabs[it] },
        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
    )
}

/** The tab's pane, or the page's loading and failure where the pack is not read yet. */
@Composable
internal fun CataloguePackPageBody(page: CataloguePackPage, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Box(modifier) {
        when (val st = page.detail) {
            DetailState.Loading -> CenteredProgress(Modifier.fillMaxSize())
            is DetailState.Error -> RetryStateBlock(
                title      = s.browseDetailErrorTitle,
                message    = st.message,
                retryLabel = s.browseRetry,
                onRetry    = page.onRetry,
                modifier   = Modifier.fillMaxWidth().padding(32.dp),
            )
            is DetailState.Loaded -> PackBody(
                details = st.details,
                tab = page.tab,
                gameVersionTags = page.gameVersionTags,
                installingVersion = page.installingVersion,
                onInstall = { v -> page.onInstall(st.details, v) },
            )
        }
    }
}

/** The pack's tabs, and only those it has something behind. */
internal enum class PackTab { Description, Versions, Changelog, Gallery }

private fun PackTab.label(s: AppStrings): String = when (this) {
    PackTab.Description -> s.modPageTabDescription
    PackTab.Versions -> s.modPageTabVersions
    PackTab.Changelog -> s.modPageTabChangelog
    PackTab.Gallery -> s.modPageTabGallery
}

private fun tabsOf(d: CataloguePackDetails): List<PackTab> = buildList {
    add(PackTab.Description)
    if (d.versions.isNotEmpty()) add(PackTab.Versions)
    if (d.versions.any { !it.changelog.isNullOrBlank() }) add(PackTab.Changelog)
    if (galleryOf(d).isNotEmpty()) add(PackTab.Gallery)
}

/** The build an install with no choice made reaches for: the source's pointer, else the newest. */
internal fun latestOf(d: CataloguePackDetails): CataloguePackVersion? =
    d.versions.firstOrNull { it.id == d.latestVersionId } ?: d.versions.firstOrNull()

/**
 * The pack's shots, its banner first where the source keeps it apart from them.
 *
 * The page has no banner of its own any more, the header is the project page's,
 * and a picture a curator chose to lead with belongs at the head of the gallery
 * rather than nowhere.
 */
internal fun galleryOf(d: CataloguePackDetails): List<CatalogueGalleryItem> {
    val banner = d.bannerUrl ?: return d.gallery
    return if (d.gallery.any { it.full == banner || it.thumb == banner }) {
        d.gallery
    } else {
        listOf(CatalogueGalleryItem(full = banner, thumb = banner)) + d.gallery
    }
}

// ClipEntry is still experimental; the project page's copy action carries the same opt-in.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PackHeader(
    packId: String,
    details: CataloguePackDetails?,
    installingVersion: String?,
    installError: String?,
    onInstall: (CataloguePackDetails, CataloguePackVersion) -> Unit,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    ProjectHeader(
        title = details?.title ?: packId,
        // Mirror summaries sometimes ship a tagline that is the name again.
        description = details?.tagline?.takeIf { it.isNotBlank() && !it.equals(details.title, ignoreCase = true) },
        iconUrl = details?.iconUrl,
        facts = {
            if (details != null) {
                details.downloads?.let { HeaderStat(NxIcon.Download, compactCount(it, s), s.modPageStatDownloads) }
                details.followers?.let { HeaderStat(NxIcon.Favorite, compactCount(it, s), s.modPageStatFollowers) }
                if (details.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        details.tags.forEach { tag ->
                            NxMetaChip(
                                s.modrinthCategory(tag),
                                tone = NxMetaChipTone.Surface,
                                onClick = tagSearch(details.projectType, null, ProjectTag.Category(tag)),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (details != null) {
                val latest = latestOf(details)
                // Labelled, as the project page's own button is. A bare glyph in a
                // corner of a picture was the one action on the page and the one a
                // reader had to guess at.
                NxButton(
                    label = if (installingVersion != null) s.modPageInstalling else s.browseDetailInstallButton,
                    onClick = { latest?.let { onInstall(details, it) } },
                    icon = NxIcon.Download,
                    enabled = installingVersion == null && latest != null,
                )
                details.pageUrl?.let { url ->
                    NxKebabButton(contentDescription = s.contentActionDetails) { dismiss ->
                        NxMenuItem(label = s.modPageOpenInCatalogue, icon = NxIcon.OpenInNew) {
                            uriHandler.openUri(url)
                            dismiss()
                        }
                        NxMenuItem(label = s.modPageCopyLink, icon = NxIcon.ContentCopy) {
                            scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(url))) }
                            dismiss()
                        }
                    }
                }
            }
        },
        notes = {
            installError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxColor.status(Status.Error, text = true),
                    maxLines = 3,
                    modifier = Modifier.widthIn(max = ERROR_MEASURE),
                )
            }
        },
    )
}

private val ERROR_MEASURE = 320.dp

@Composable
private fun PackBody(
    details: CataloguePackDetails,
    tab: PackTab,
    gameVersionTags: List<ModrinthGameVersion>,
    installingVersion: String?,
    onInstall: (CataloguePackVersion) -> Unit,
) {
    val s = LocalStrings.current
    // A body link to a video (direct file or a service page) opens in-app; a link
    // to a project opens its page here, and the rest go where links go.
    val follow = rememberLinkFollower()
    var videoLink by remember { mutableStateOf<String?>(null) }
    val builds = remember(details) { details.versions.map { it.toBuild() } }
    val byId = remember(details) { details.versions.associateBy { it.id } }
    when (tab) {
        PackTab.Description -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val body = details.bodyMarkdown
            if (!body.isNullOrBlank()) {
                MarkdownHtml(
                    markdown = body,
                    modifier = Modifier.fillMaxWidth(),
                    onLink   = { url -> if (isPlayableVideoUrl(url)) videoLink = url else follow(url) },
                )
            } else {
                // A source that says nothing about its pack is not a page that
                // failed to load, and must not read like one.
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    Text(s.browseDetailNoDescription, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                }
            }
        }
        PackTab.Versions -> BuildsTable(
            builds = builds,
            gameVersionTags = gameVersionTags,
            onOpenBuild = null,
            // Any build, not only the newest: a pack is installed whole, so the row
            // is where a reader picks an older one on purpose.
            rowAction = { b ->
                byId[b.id]?.let { v ->
                    NxIconButton(
                        icon = NxIcon.Download,
                        contentDescription = s.modPageInstallShort,
                        onClick = { onInstall(v) },
                        enabled = installingVersion == null,
                        tint = NxColor.lead(),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        PackTab.Changelog -> ChangelogList(builds, Modifier.fillMaxSize())
        PackTab.Gallery -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ImageGallery(media = remember(details) { galleryMedia(galleryOf(details)) }, modifier = Modifier.fillMaxWidth())
        }
    }
    videoLink?.let { url -> FullscreenVideo(url = url, onDismiss = { videoLink = null }) }
}

/** What the rail's project view draws for a pack, in the terms it draws a project in. */
internal fun openPackOf(
    targetKey: String,
    d: CataloguePackDetails,
    gameVersionTags: List<ModrinthGameVersion>,
    s: AppStrings,
    /**
     * A sign-in provider's name by its registry id. The provider names itself; one
     * this build has not registered goes by its id, the way the launch gate's own
     * messages say it.
     */
    providerName: (String) -> String = { it },
): OpenProject {
    val latest = latestOf(d)
    return OpenProject(
        targetKey = targetKey,
        title = d.title,
        slug = d.slug ?: d.id,
        source = ProjectSource.Catalogue,
        projectType = d.projectType,
        gameVersions = gameVersionChips(d.gameVersions, gameVersionTags),
        loaders = d.loaders,
        categories = d.tags,
        clientSide = d.clientSide,
        serverSide = d.serverSide,
        licenseId = d.licenseId,
        licenseName = d.licenseName,
        publishedAt = relativeAge(d.publishedAt, s).takeIf { it.isNotBlank() },
        publishedExact = formatBuildTimestamp(d.publishedAt),
        updatedAt = relativeAge(d.updatedAt, s).takeIf { it.isNotBlank() },
        updatedExact = formatBuildTimestamp(d.updatedAt),
        links = d.links.map { ProjectLink(linkKindOf(it.kind), it.url, it.label) },
        creators = d.creators.map { ProjectCreator(it.name, it.role, it.avatarUrl, it.owner) },
        sizeBytes = latest?.sizeBytes,
        runtime = d.runtimeLabel,
        signIn = d.auth?.providerKeys.orEmpty().map(providerName),
        modsCount = latest?.modsCount,
        // A source that names a licence or a first release names them always, so
        // neither present means the source has no such field to fill.
        answersLicence = d.licenseId != null || d.licenseName != null,
        answersPublished = d.publishedAt != null,
    )
}

private fun linkKindOf(kind: CatalogueLinkKind): ProjectLinkKind = when (kind) {
    CatalogueLinkKind.Issues -> ProjectLinkKind.Issues
    CatalogueLinkKind.Source -> ProjectLinkKind.Source
    CatalogueLinkKind.Wiki -> ProjectLinkKind.Wiki
    CatalogueLinkKind.Discord -> ProjectLinkKind.Discord
    CatalogueLinkKind.Donate -> ProjectLinkKind.Donate
}


internal sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val details: CataloguePackDetails) : DetailState()
    data class Error(val message: String) : DetailState()
}
