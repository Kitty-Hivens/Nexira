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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.PackPageStatus
import hivens.catalogue.api.PackVisit
import hivens.catalogue.api.ProjectBuild
import hivens.packs.api.InstallRequest
import hivens.packs.api.InstallStatus
import hivens.packs.api.PackKeys
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.ImageGallery
import hivens.ui.components.galleryMedia
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.feature.catalogue.project.BuildsTable
import hivens.ui.feature.catalogue.project.ChangelogList
import hivens.ui.feature.catalogue.project.HeaderStat
import hivens.ui.feature.catalogue.project.ProjectHeader
import hivens.ui.feature.catalogue.project.compactCount
import hivens.ui.feature.catalogue.project.rememberLinkFollower
import hivens.ui.feature.catalogue.rememberPageVisit
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
import hivens.ui.screens.console.copyPlainText
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.ProvideScoped
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotId
import hivens.widget.model.SourceKey
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.launch

/**
 * A pack's page in the catalogue, whichever source lists it.
 *
 * Built from the project page's own parts, because a reader moving between a mod
 * and the pack it sits in should not meet two pages: the same header, the same
 * tabs, the same table of builds and the same changelog, and the facts in the
 * right rail's project view, which reads what the catalogue module publishes for
 * the page. A source fills what it has. The catalogue counts downloads and credits a
 * team. The mirror names the runtime, the sign-in and the mods a build holds. What
 * neither says is left out rather than shown empty.
 *
 * The page is the catalogue module's, read through [CatalogueKeys.PackPage]. The
 * install is the packs module's command, and its progress is read back from the
 * packs module's installs, so leaving this screen does not cancel the download and
 * coming back finds it again.
 */
@Composable
fun CataloguePackDetailScreen(
    origin: String,
    packId: String,
    onBack: () -> Unit,
    onInstalled: (instanceId: String) -> Unit,
) {
    val families = LocalSurfaceFamilies.current
    val show = rememberScopedCommand(CatalogueKeys.ShowPack)
    val hide = rememberScopedCommand(CatalogueKeys.HidePack)
    val retry = rememberScopedAction(CatalogueKeys.RetryPack)
    val installPack = rememberScopedCommand(PackKeys.InstallPack)
    val dismissInstall = rememberScopedCommand(PackKeys.DismissInstall)
    val loadTags = rememberScopedAction(CatalogueKeys.LoadTags)

    PuppetScreen("CataloguePack.$packId")
    // Back is the top-bar breadcrumb's job, but automation still needs a handle on it.
    PuppetClick("catalogue.detail.back") { onBack() }

    // This visit of the page. The catalogue module opens it on the page as it was last
    // read rather than on a spinner, and the rail follows it for as long as it is shown.
    val visit = rememberPageVisit(
        key = origin to packId,
        show = show?.let { cmd -> { v: String -> cmd(PackVisit(origin, packId, v)) } },
        hide = hide,
    )
    val page = rememberScopedSource(CatalogueKeys.PackPage).valueOrNull()?.takeIf { it.visit == visit }
    val status = page?.status ?: PackPageStatus.Loading

    // This visit, as the rail's owner: a page arriving over this one takes the rail.
    DisposableEffect(visit) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW, visit)
        onDispose { families.reset(RIGHT_RAIL_SURFACE, visit) }
    }

    // The install is the packs module's and runs on past this screen. Matched on the
    // pack, so a return here finds an install started before the reader left.
    val installs = rememberScopedSource(PackKeys.Installs).valueOrNull().orEmpty()
    val active = installs.values.firstOrNull { it.origin == origin && it.packId == packId }
    val installingVersion = (active?.status as? InstallStatus.Running)?.let { active.versionId }
    val installError = (active?.status as? InstallStatus.Failed)?.message

    // Success goes to the installed pack, then forgets the finished install so a later
    // reinstall starts clean.
    LaunchedEffect(active?.key, active?.status) {
        val done = active?.status as? InstallStatus.Succeeded ?: return@LaunchedEffect
        dismissInstall?.invoke(active.key)
        onInstalled(done.packId)
    }

    val install: (PackDetails, ProjectBuild) -> Unit = { details, build ->
        installPack?.invoke(InstallRequest(details.origin, details.id, build.id))
    }

    LaunchedEffect(loadTags != null) { loadTags?.invoke() }
    val tags = rememberScopedSource(CatalogueKeys.Tags).valueOrNull()

    val tabState = rememberSaveable(origin, packId, stateSaver = Saver(save = { it.name }, restore = { PackTab.valueOf(it) })) {
        mutableStateOf(PackTab.Description)
    }
    var tab by tabState
    val loaded = (status as? PackPageStatus.Loaded)?.details
    val tabs = remember(loaded) { loaded?.let(::tabsOf) ?: listOf(PackTab.Description) }
    LaunchedEffect(tabs) { if (tab !in tabs) tab = PackTab.Description }
    val pagePart = CataloguePackPage(
        packId = packId,
        detail = status,
        tab = tabState,
        tabs = tabs,
        gameVersionTags = tags?.gameVersions.orEmpty(),
        installingVersion = installingVersion,
        installError = installError,
        onInstall = install,
        onRetry = { retry?.invoke() },
    )

    // A widget surface, the way the project page is: the header and the tabs in
    // `header`, the tab's pane in `body`, each slot on the panel it always sat on.
    // The widgets read the page over this page's level, where nothing outside the
    // page can reach it.
    ProvideScoped(CataloguePackPageSource, pagePart) {
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
            d.latest?.let { install(d, it) }
        }
    }
}

/** The catalogue pack page's surface, see [CataloguePackPage]. */
internal const val CATALOGUE_PACK_SURFACE = "catalogue.pack"

/**
 * What the catalogue pack page's widgets share: the page as read, the tab it
 * shows, and the install it offers. Per visit, for the reason the project page's
 * [hivens.ui.feature.catalogue.project.ProjectPage] is.
 */
internal class CataloguePackPage(
    val packId: String,
    val detail: PackPageStatus,
    tab: MutableState<PackTab>,
    /** The tabs the pack has something behind. */
    val tabs: List<PackTab>,
    val gameVersionTags: List<GameVersionTag>,
    /** The build being installed, or null. */
    val installingVersion: String?,
    val installError: String?,
    val onInstall: (PackDetails, ProjectBuild) -> Unit,
    /** Reads the pack again after a read that failed. */
    val onRetry: () -> Unit,
) {
    var tab: PackTab by tab

    val details: PackDetails? get() = (detail as? PackPageStatus.Loaded)?.details
}

/**
 * The catalogue pack page the widgets are drawn for, put over the page's own level
 * by the screen. Each of the two pages on screen during a fade carries its own down
 * its own subtree. Anywhere else the key is missing.
 */
internal val CataloguePackPageSource: SourceKey<CataloguePackPage> = SourceKey("catalogue.pack.page", ScopeLevel.Page)

/**
 * A pack page to show the page's widgets on where there is no pack: the palette, and
 * the editor drawing one of them off its page. Display data only, and its install
 * and retry do nothing.
 */
internal fun sampleCataloguePackPage(): CataloguePackPage {
    val build = ProjectBuild(id = "1.0.0", name = "1.0.0", versionNumber = "1.0.0", versionType = "release", gameVersions = emptyList(), loaders = emptyList(), datePublished = "")
    val details = PackDetails(
        origin = PackOrigins.MIRROR,
        id = "sample",
        title = "Sample Pack",
        tagline = "A pack as this page shows one.",
        versions = listOf(build),
        latestVersionId = build.id,
        gameVersions = listOf("1.21.1"),
        loaders = listOf("neoforge"),
        bodyMarkdown = "What a pack says about itself appears here.",
    )
    return CataloguePackPage(
        packId = details.id,
        detail = PackPageStatus.Loaded(details),
        tab = mutableStateOf(PackTab.Description),
        tabs = listOf(PackTab.Description, PackTab.Versions),
        gameVersionTags = emptyList(),
        installingVersion = null,
        installError = null,
        onInstall = { _, _ -> },
        onRetry = {},
    )
}

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
            PackPageStatus.Loading -> CenteredProgress(Modifier.fillMaxSize())
            is PackPageStatus.Failed -> RetryStateBlock(
                title      = s.browseDetailErrorTitle,
                message    = st.message ?: s.browseDetailErrorMessage,
                retryLabel = s.browseRetry,
                onRetry    = page.onRetry,
                modifier   = Modifier.fillMaxWidth().padding(32.dp),
            )
            is PackPageStatus.Loaded -> PackBody(
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

private fun tabsOf(d: PackDetails): List<PackTab> = buildList {
    add(PackTab.Description)
    if (d.versions.isNotEmpty()) add(PackTab.Versions)
    if (d.versions.any { !it.changelog.isNullOrBlank() }) add(PackTab.Changelog)
    if (d.gallery.isNotEmpty()) add(PackTab.Gallery)
}

@Composable
private fun PackHeader(
    packId: String,
    details: PackDetails?,
    installingVersion: String?,
    installError: String?,
    onInstall: (PackDetails, ProjectBuild) -> Unit,
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
                val latest = details.latest
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
                            scope.launch { clipboard.copyPlainText(url) }
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
                    color = NxColor.status(Status.Error),
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
    details: PackDetails,
    tab: PackTab,
    gameVersionTags: List<GameVersionTag>,
    installingVersion: String?,
    onInstall: (ProjectBuild) -> Unit,
) {
    val s = LocalStrings.current
    // A body link to a video (direct file or a service page) opens in-app; a link
    // to a project opens its page here, and the rest go where links go.
    val follow = rememberLinkFollower()
    var videoLink by remember { mutableStateOf<String?>(null) }
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
            builds = details.versions,
            gameVersionTags = gameVersionTags,
            onOpenBuild = null,
            // Any build, not only the newest: a pack is installed whole, so the row
            // is where a reader picks an older one on purpose.
            rowAction = { b ->
                NxIconButton(
                    icon = NxIcon.Download,
                    contentDescription = s.modPageInstallShort,
                    onClick = { onInstall(b) },
                    enabled = installingVersion == null,
                    tint = NxColor.lead(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        PackTab.Changelog -> ChangelogList(details.versions, Modifier.fillMaxSize())
        PackTab.Gallery -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ImageGallery(media = remember(details) { galleryMedia(details.gallery) }, modifier = Modifier.fillMaxWidth())
        }
    }
    videoLink?.let { url -> FullscreenVideo(url = url, onDismiss = { videoLink = null }) }
}
