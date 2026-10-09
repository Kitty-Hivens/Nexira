package hivens.ui.feature.catalogue.project

import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.launcher.instance.InstanceContentScanner
import hivens.ui.feature.catalogue.browse.InstallChooser
import hivens.ui.feature.catalogue.browse.InstallSubject
import hivens.ui.feature.catalogue.browse.InstallProblem
import hivens.ui.feature.catalogue.browse.ProjectTag
import hivens.ui.feature.catalogue.browse.tagSearch
import hivens.ui.feature.catalogue.browse.label
import hivens.ui.feature.catalogue.browse.reason
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTabRow
import hivens.ui.nx.RetryStateBlock
import hivens.ui.components.ImageGallery
import hivens.ui.components.modrinthGalleryMedia
import hivens.ui.render.MarkdownHtml
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.nio.file.Path
import java.awt.datatransfer.StringSelection
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * The project page: a header, the tabs, and the body.
 *
 * No way back of its own. The shell already carries one, twice: the top bar's
 * arrow and the breadcrumb that ends on this page. A third, wedged between the
 * window edge and the project's own mark, was one more thing to read before the
 * page starts and pushed the mark off the margin every other page keeps.
 *
 * Nothing else, on purpose. The compatibility, links and details blocks live in
 * `appshell.rightrail`, which is a shell surface and therefore already beside
 * this page on every screen -- a page carrying its own right-hand column would
 * stand a third column next to it. The page publishes what it knows and the rail
 * switches to the family that reads it, so neither reaches into the other.
 */
@Composable
fun ModDetailScreen(
    target: ModTarget,
    /**
     * Opens one build's own page. Carries the build's number as well as its id,
     * because the breadcrumb needs a name on the first frame and the row that was
     * clicked already has one.
     */
    onOpenVersion: (versionId: String, versionNumber: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modrinth: ModrinthClient = koinInject()
    val repo: IPackRepository = koinInject()
    val scanner: InstanceContentScanner = koinInject()
    val openProject: OpenProjectState = koinInject()
    val dataDir: Path = koinInject()
    val appScope: CoroutineScope = koinInject()
    val installer: ModInstaller = koinInject()
    val mirrorClient: SmrtPackClient = koinInject()
    val families = LocalSurfaceFamilies.current
    val s = LocalStrings.current

    val state = remember(target) {
        ModDetailState(
            target, modrinth, repo, dataDir, scanner, openProject,
            strings = s, installScope = appScope, installer = installer, mirrorLookup = mirrorClient.asMirrorLookup(),
        )
    }
    var reloadTick by remember(state) { mutableStateOf(0) }
    // Saveable, not remembered. The only way to a build's page is the Versions
    // tab, and the shell keeps a screen's saveable state across a visit -- so
    // without this, Back from a build always landed on Description, which is not
    // the tab anybody left from.
    val tabState = rememberSaveable(
        state,
        stateSaver = Saver(save = { it.name }, restore = { ModPageTab.valueOf(it) }),
    ) { mutableStateOf(ModPageTab.Description) }
    var tab by tabState
    val gallery = remember(state.project) { modrinthGalleryMedia(state.project?.gallery.orEmpty()) }
    // A tab with nothing behind it is not drawn, which is what the reference does:
    // a project with no shots has no gallery to open, and a tab that leads to an
    // empty pane is a click that tells the reader nothing they could not have been
    // told by its absence. Asked once the page has loaded: before that every page
    // has no shots, and a Gallery tab restored on the way back was reset to
    // Description on the first frame.
    LaunchedEffect(gallery, tab, state.loading) {
        tab = tabOnceLoaded(tab, state.loading, noGallery = gallery.isEmpty())
    }
    LaunchedEffect(state, reloadTick) { state.load() }

    // The rail follows the page for exactly as long as the page is mounted. Tied
    // to the composition rather than to navigation so every way out -- Back, a
    // link, a crash recovery remount -- puts the rail back the same way.
    DisposableEffect(state) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW, state)
        state.claim()
        onDispose {
            families.reset(RIGHT_RAIL_SURFACE, state)
            state.clear()
        }
    }

    val page = remember(state, gallery, onOpenVersion) {
        ProjectPage(
            state = state,
            tab = tabState,
            gallery = gallery,
            onOpenVersion = { v -> onOpenVersion(v.id, v.versionNumber.ifBlank { v.name }) },
            onReload = { reloadTick++ },
        )
    }

    // A widget surface: the header and the tabs in `header`, the tab's pane in
    // `body`, each slot on the panel it always sat on.
    CompositionLocalProvider(LocalProjectPage provides page) {
        Column(modifier.fillMaxSize()) {
            // The header and the tabs sit on a panel of their own, like the body under
            // them. On the bare page they lay over whatever the wallpaper had there, and
            // no ink the theme can pick reads on a picture it has never seen.
            NxSurface(
                kind = SurfaceKind.Panel,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp),
            ) {
                SlotRenderer(
                    SurfaceId(PROJECT_SURFACE),
                    SlotId("header"),
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
            NxSurface(
                kind = SurfaceKind.Panel,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            ) {
                SlotRenderer(SurfaceId(PROJECT_SURFACE), SlotId("body"), Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The pane the page's tab shows. Only the description scrolls as a page: the
 * versions pane is a list beside its notes and does its own scrolling in two
 * places, so wrapping it in a third would move the whole thing to reach the bottom
 * of either.
 */
@Composable
internal fun ProjectPageBody(page: ProjectPage, modifier: Modifier = Modifier) {
    val state = page.state
    Box(modifier) {
        when (page.tab) {
            ModPageTab.Description -> Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Sixteen all round, the same as the rail's blocks. The body
                    // and the sidebar are the same kind of card in the reference
                    // and giving one of them a different inset is what makes a
                    // page look assembled from parts.
                    .padding(16.dp),
            ) {
                Body(state, page.onReload)
            }
            ModPageTab.Versions -> VersionsPane(
                state = state,
                onOpenVersion = page.onOpenVersion,
                onReload = page.onReload,
                modifier = Modifier.fillMaxSize(),
            )
            ModPageTab.Changelog -> ChangelogPane(state, onReload = page.onReload, modifier = Modifier.fillMaxSize())
            ModPageTab.Gallery -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                // The pack gallery's own component. One strip, one lightbox, one
                // answer to a shot with no caption.
                ImageGallery(page.gallery, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Which pane the page is showing. */
internal enum class ModPageTab { Description, Versions, Changelog, Gallery }

/**
 * The tab to show once the page knows what it has: a Gallery tab on a project with
 * no shots goes back to Description. Asked only after loading, because until then
 * every project has no shots and a Gallery tab restored on the way back was lost.
 */
internal fun tabOnceLoaded(tab: ModPageTab, loading: Boolean, noGallery: Boolean): ModPageTab =
    if (!loading && noGallery && tab == ModPageTab.Gallery) ModPageTab.Description else tab

@Composable
internal fun Body(state: ModDetailState, onRetry: () -> Unit) {
    val s = LocalStrings.current
    val follow = rememberLinkFollower(state.packId)
    when {
        state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = NxColor.wash(NxColor.lead(), 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp),
            )
        }
        // The lookup did not run. Distinct from a project that has no body, and
        // the only one of the three that is worth offering to try again.
        state.failed -> RetryStateBlock(
            title = s.contentTabFetchErrorTitle,
            message = s.contentTabFetchErrorGeneric,
            retryLabel = s.contentTabRetry,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            titleStyle = MaterialTheme.typography.titleMedium,
        )
        state.body != null -> MarkdownHtml(
            markdown = state.body.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            // A description that links to another mod opens that mod HERE.
            onLink = follow,
        )
        // Two different silences. The catalogue has an entry and the author left
        // it blank, or there is no entry at all and the description lives
        // somewhere this file is not linked to.
        state.source == ProjectSource.Catalogue -> Unknown(s.modPageBodyEmpty)
        else -> Unknown(s.modPageBodyUnknown)
    }
}

/** A fact the page has no answer for, said plainly instead of left blank. */
@Composable
private fun Unknown(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = NxInk.quiet,
)

// ClipEntry is still experimental; the console's copy actions carry the same
// opt-in for the same call.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Header(state: ModDetailState) {
    val scope = rememberCoroutineScope()
    val installScope = state.installScope
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val project = state.project
    val installed = state.installed

    ProjectHeader(
        title = state.title,
        description = state.description,
        iconUrl = project?.iconUrl ?: state.mirror?.iconUrl,
        facts = {
            if (project != null) {
                // Metrics are separate facts and want air between them; the
                // chips are one list and want to read as one.
                HeaderStat(NxIcon.Download, compactCount(project.downloads, s), s.modPageStatDownloads)
                HeaderStat(NxIcon.Favorite, compactCount(project.followers, s), s.modPageStatFollowers)
                // All of them, not the first three. The reference shows the whole
                // set here AND again as a block in the rail; taking three was my
                // own edit, and it silently said a project has three tags.
                if (project.categories.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        project.categories.forEach { name ->
                            NxMetaChip(
                                s.modrinthCategory(name),
                                tone = NxMetaChipTone.Surface,
                                onClick = tagSearch(project.projectType, state.packId, ProjectTag.Category(name)),
                            )
                        }
                    }
                }
            } else {
                // Counts belong to a catalogue. A file found on disk has none,
                // and inventing a zero would read as an answer. One the mirror
                // knows says who publishes it instead, or that the mirror does.
                val mirror = state.mirror
                NxMetaChip(
                    if (mirror != null) mirror.publishedOn ?: MIRROR_NAME else s.modPageLocalFile,
                    tone = NxMetaChipTone.Surface,
                )
                installed?.version?.let { NxMetaChip(it, tone = NxMetaChipTone.Surface) }
            }
        },
        actions = {
            // No "open page" button. The reference has none, and for a good
            // reason: on a project page that action is the page you are already
            // standing on. Ours is worth having because the catalogue's copy is
            // somewhere else, but it is not what a reader came here to do, so it
            // goes in the overflow the way every other secondary route does.
            //
            // Whether an install is possible at all is known from the route.
            // WHAT goes in the slot needs the pack read off disk, and until it
            // lands [InstallAction.None] draws nothing, so this reserves the
            // fact rather than the width: the button still appears a moment in.
            // Reserving the width would mean knowing the label, and the label
            // names the pack.
            if (state.installPossible) {
                Box(contentAlignment = Alignment.Center) { InstallButton(state, installScope) }
            }
            val pageUrl = project?.let { "https://modrinth.com/${it.projectType}/${it.slug}" }
            val homepage = installed?.homepageUrl?.takeIf { it.isNotBlank() }
            if (pageUrl != null || homepage != null) {
                NxKebabButton(contentDescription = s.contentActionDetails) { dismiss ->
                    if (pageUrl != null) {
                        NxMenuItem(label = s.modPageOpenInCatalogue, icon = NxIcon.OpenInNew) {
                            uriHandler.openUri(pageUrl)
                            dismiss()
                        }
                        NxMenuItem(label = s.modPageCopyLink, icon = NxIcon.ContentCopy) {
                            // The write suspends now, so it rides the composition's
                            // scope: the menu dismisses on this frame and the
                            // clipboard lands on its own, which is what the console's
                            // copy actions already do.
                            scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(pageUrl))) }
                            dismiss()
                        }
                    }
                    if (homepage != null) {
                        NxMenuItem(label = s.modPageHomepage, icon = NxIcon.Language) {
                            uriHandler.openUri(homepage)
                            dismiss()
                        }
                    }
                }
            }
        },
        notes = {
            installed?.version?.let {
                Text(
                    s.modPageInstalledVersion(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                )
            }
            InstallNotes(state)
        },
    )
}

/**
 * What the last install from this page came to, under the button that was pressed:
 * the project page's header and a single build's page both draw it.
 */
@Composable
internal fun InstallNotes(state: ModDetailState) {
    val s = LocalStrings.current
    // Why nothing happened, under the button that was pressed, naming the
    // two things that decided it. Without this the click did nothing and
    // said nothing, which reads as the launcher having ignored it.
    if (state.installNoBuild) {
        // Names the axes that are actually KNOWN. Filling a blank one with
        // the unknown placeholder produced "no build for Unknown / Unknown",
        // which is a sentence about our own ignorance rather than about
        // the pack.
        val target = listOf(state.packMcVersion, state.packLoader.takeIf { it.isNotBlank() }?.let(::loaderLabel).orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        Text(
            if (target.isBlank()) s.modPageNoBuildAny else s.modPageNoBuildFor(target),
            style = MaterialTheme.typography.labelSmall,
            color = NxColor.status(Status.Warning, text = true),
        )
    }
    // Said under the button, where the click was. A mod that landed without
    // something it requires is installed and will not run, and this is the
    // only place on the page that would ever mention it.
    if (state.installMissing.isNotEmpty()) {
        Text(
            s.modPageInstallMissing(state.installMissing.size),
            style = MaterialTheme.typography.labelSmall,
            color = NxColor.status(Status.Warning, text = true),
        )
    }
    // Each one by name and why, because the count alone sends the reader
    // looking for what it means. A long list stops at a few; the rest is a
    // count, since a column of twenty reasons under a button is not read.
    state.installLeftOut.take(MAX_LEFT_OUT_LINES).forEach { left ->
        Text(
            s.installSkipLine(left.title, left.skip.reason(s)),
            style = MaterialTheme.typography.labelSmall,
            color = NxInk.quiet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = LEFT_OUT_MEASURE),
        )
    }
    // Why nothing ran, said where the click was. A refused install and a
    // broken one are different things to do something about.
    state.installRefusal?.let { refusal ->
        Text(
            InstallProblem.Refused(refusal).label(s),
            style = MaterialTheme.typography.labelSmall,
            color = NxColor.status(Status.Warning, text = true),
        )
    }
}

/** The mirror by its own name, which is a brand and the same in every language. */
private const val MIRROR_NAME = "Hivens"

/** How many left-out projects are named under the button before the rest is only counted. */
private const val MAX_LEFT_OUT_LINES = 3
private val LEFT_OUT_MEASURE = 320.dp

/**
 * The page's primary action, and nothing where there is none.
 *
 * A page reached without a pack behind it has nowhere to put the mod, so it shows
 * no button at all rather than a disabled one: a greyed-out Install invites a
 * click and then explains, where an absent one says the same thing by saying
 * nothing.
 */
@Composable
private fun InstallButton(state: ModDetailState, scope: CoroutineScope) {
    val s = LocalStrings.current
    when (val action = state.install) {
        InstallAction.None -> Unit
        InstallAction.Choose -> state.project?.let { InstallChooser(InstallSubject(it.id, it.title, it.iconUrl)) }
        is InstallAction.Present -> NxButton(
            label = s.modPageInstalledIn(action.packName),
            onClick = {},
            icon = NxIcon.Check,
            enabled = false,
            style = NxButtonStyle.Secondary,
        )
        is InstallAction.Install -> NxButton(
            // A failed install says so on the button that failed and offers the
            // same action again, rather than reverting to its first wording as if
            // nothing had happened.
            label = when {
                state.installing -> s.modPageInstalling
                state.installFailed -> s.modPageInstallRetry
                else -> s.modPageInstallInto(action.packName)
            },
            // A retry repeats what failed: a row's build when a row asked for one.
            onClick = { scope.launch(Dispatchers.Main) { if (state.installFailed) state.retryInstall() else state.installIntoPack() } },
            icon = if (state.installFailed) NxIcon.Refresh else NxIcon.Download,
            enabled = !state.installing,
        )
    }
}


/**
 * The page's tabs.
 *
 * Description, versions and the changelog are always there, because every project
 * has all three even when one of them is empty. The gallery is the exception and
 * appears only where there are shots.
 */
@Composable
internal fun Tabs(
    active: ModPageTab,
    onSelect: (ModPageTab) -> Unit,
    modifier: Modifier = Modifier,
    hasGallery: Boolean = false,
) {
    val s = LocalStrings.current
    // The gallery appears only where there are shots, the way the reference does
    // it. A tab that opens an empty pane is a click that tells a reader nothing
    // its absence would not have told them.
    val tabs = buildList {
        add(ModPageTab.Description to s.modPageTabDescription)
        add(ModPageTab.Versions to s.modPageTabVersions)
        add(ModPageTab.Changelog to s.modPageTabChangelog)
        if (hasGallery) add(ModPageTab.Gallery to s.modPageTabGallery)
    }

    // The row and its travelling mark are the library's now: the pack settings
    // sheet wanted the same tabs, and two copies of one control drift apart.
    NxTabRow(
        tabs = tabs.map { it.second },
        selected = tabs.indexOfFirst { it.first == active }.coerceAtLeast(0),
        onSelect = { onSelect(tabs[it].first) },
        modifier = modifier,
    )
}
