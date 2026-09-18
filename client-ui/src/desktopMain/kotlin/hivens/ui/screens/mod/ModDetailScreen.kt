package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.IPackRepository
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.RetryStateBlock
import hivens.ui.components.ImageGallery
import hivens.ui.components.modrinthGalleryMedia
import hivens.ui.render.MarkdownHtml
import hivens.ui.screens.versions.VersionBrowser
import hivens.ui.screens.versions.pickerVersionsOf
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import hivens.ui.theme.familyForText
import hivens.widget.api.LocalSurfaceFamilies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * The project page: a header, the tabs, and the body.
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modrinth: ModrinthClient = koinInject()
    val repo: IPackRepository = koinInject()
    val scanner: InstanceContentScanner = koinInject()
    val openProject: OpenProjectState = koinInject()
    val dataDir: Path = koinInject()
    val families = LocalSurfaceFamilies.current

    val state = remember(target) {
        ModDetailState(target, modrinth, repo, dataDir, scanner, openProject)
    }
    var reloadTick by remember(state) { mutableStateOf(0) }
    var tab by remember(state) { mutableStateOf(ModPageTab.Description) }
    val gallery = remember(state.project) { modrinthGalleryMedia(state.project?.gallery.orEmpty()) }
    // A tab with nothing behind it is not drawn, which is what the reference does:
    // a project with no shots has no gallery to open, and a tab that leads to an
    // empty pane is a click that tells the reader nothing they could not have been
    // told by its absence.
    LaunchedEffect(gallery, tab) {
        if (gallery.isEmpty() && tab == ModPageTab.Gallery) tab = ModPageTab.Description
    }
    LaunchedEffect(state, reloadTick) { state.load() }

    // The rail follows the page for exactly as long as the page is mounted. Tied
    // to the composition rather than to navigation so every way out -- Back, a
    // link, a crash recovery remount -- puts the rail back the same way.
    DisposableEffect(state) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW)
        onDispose {
            families.reset(RIGHT_RAIL_SURFACE)
            state.clear()
        }
    }

    Column(modifier.fillMaxSize()) {
        Header(state, onBack)
        // The tabs are page chrome, above the card and outside its scroll. They
        // used to be the first thing inside it, so opening a long description and
        // reading two screens down left no way back to Versions without scrolling
        // to the top first -- and nothing on screen said the tabs still existed.
        Tabs(
            active = tab,
            onSelect = { tab = it },
            hasGallery = gallery.isNotEmpty(),
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        )
        NxSurface(
            level = NxSurfaceLevel.Raised,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        ) {
            when (tab) {
                // Only the description scrolls as a page. The versions pane is a
                // list beside its notes and does its own scrolling in two places,
                // so wrapping it in a third would move the whole thing to reach the
                // bottom of either.
                ModPageTab.Description -> Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // Sixteen all round, the same as the rail's blocks. The body
                        // and the sidebar are the same kind of card in the reference
                        // and giving one of them a different inset is what makes a
                        // page look assembled from parts.
                        .padding(16.dp),
                ) {
                    Body(state) { reloadTick++ }
                }
                ModPageTab.Versions -> VersionsPane(state, Modifier.fillMaxSize())
                ModPageTab.Changelog -> ChangelogPane(state, Modifier.fillMaxSize())
                ModPageTab.Gallery -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    // The pack gallery's own component. One strip, one lightbox, one
                    // answer to a shot with no caption.
                    ImageGallery(gallery, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Which pane the page is showing. */
internal enum class ModPageTab { Description, Versions, Changelog, Gallery }

/**
 * Every build the catalogue has, in the same list the version modal uses.
 *
 * The same furniture on purpose: picking a build of a mod is one decision and it
 * should not look like two different screens depending on where the reader
 * started. What differs is where the action sits, which is under the notes here
 * because a tab has no footer of its own.
 */
@Composable
internal fun VersionsPane(state: ModDetailState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // Asked when the tab is opened, not when the page is. Keyed on the project so
    // a second visit to a different mod asks again.
    LaunchedEffect(state) { state.loadVersions() }

    val all = state.versions
    val rows = remember(all, state.packMcVersion, state.packLoaders, s) {
        pickerVersionsOf(all.orEmpty(), state.packMcVersion, state.packLoaders, installedId = null, s = s)
    }

    if (state.versionsFailed) {
        RetryStateBlock(
            title = s.contentTabFetchErrorTitle,
            message = s.contentVersionsLoadFailed,
            retryLabel = s.contentTabRetry,
            onRetry = { scope.launch { state.versions = null; state.loadVersions() } },
            modifier = modifier.padding(20.dp),
            titleStyle = MaterialTheme.typography.titleMedium,
        )
        return
    }

    VersionBrowser(
        versions = rows,
        loading = all == null,
        onSelectionChange = {},
        modifier = modifier,
        detailAction = { picked ->
            // No action where there is no pack to act on. A page opened from
            // nowhere in particular can still be read; it just cannot install.
            if (state.install != InstallAction.None) {
                NxButton(
                    label = when {
                        state.installing -> s.modPageInstalling
                        state.installFailed -> s.modPageInstallRetry
                        else -> s.modPageInstallBuild(picked.label)
                    },
                    onClick = {
                        all?.firstOrNull { it.id == picked.id }
                            ?.let { v -> scope.launch { state.installVersion(v) } }
                    },
                    icon = if (state.installFailed) NxIcon.Refresh else NxIcon.Download,
                    enabled = !state.installing,
                )
            }
        },
    )
}

@Composable
internal fun Body(state: ModDetailState, onRetry: () -> Unit) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    when {
        state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = NxTheme.colors.primary.copy(alpha = 0.6f),
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
            onLink = { uriHandler.openUri(it) },
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
    color = NxTheme.colors.textSecondary,
)

@Composable
internal fun Header(state: ModDetailState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val project = state.project
    val installed = state.installed

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // Top, not centre. With a two-line tagline and a row of counts under it,
        // centring hangs the 96dp icon halfway down the block and the title stops
        // sitting on the same line as anything.
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(NxIcon.ArrowBack, contentDescription = null, tint = NxTheme.colors.textPrimary, size = 20.dp)
        }

        ProjectIcon(project?.iconUrl, state.title)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = NxTheme.colors.textPrimary,
                // Semibold and tight, the way the reference sets it. Bold at this
                // size reads as a banner rather than as a name.
                fontWeight = FontWeight.SemiBold,
                lineHeight = TITLE_SIZE,
                fontFamily = familyForText(state.title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.textSecondary,
                    // The tagline is set to a reading measure rather than to the
                    // column: run across a wide window it becomes one long line
                    // nobody tracks back from.
                    modifier = Modifier.widthIn(max = SUMMARY_MEASURE),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
          }
            // 26 across between facts, 8 down when they wrap. Measured: the counts
            // are separate statements and want real air, the categories are one
            // list and want none.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (project != null) {
                    // Metrics are separate facts and want air between them; the
                    // chips are one list and want to read as one.
                    Stat(NxIcon.Download, compactCount(project.downloads, s), s.modPageStatDownloads)
                    Stat(NxIcon.Favorite, compactCount(project.followers, s), s.modPageStatFollowers)
                    // All of them, not the first three. The reference shows the whole
                    // set here AND again as a block in the rail; taking three was my
                    // own edit, and it silently said a project has three tags.
                    if (project.categories.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            project.categories.forEach { NxMetaChip(it, tone = NxMetaChipTone.Surface) }
                        }
                    }
                } else {
                    // Counts belong to a catalogue. A file found on disk has none,
                    // and inventing a zero would read as an answer.
                    NxMetaChip(s.modPageLocalFile, tone = NxMetaChipTone.Surface)
                    installed?.version?.let { NxMetaChip(it, tone = NxMetaChipTone.Surface) }
                }
            }
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // No "open page" button. The reference has none, and for a good
                // reason: on a project page that action is the page you are already
                // standing on. Ours is worth having because the catalogue's copy is
                // somewhere else, but it is not what a reader came here to do, so it
                // goes in the overflow the way every other secondary route does.
                //
                InstallButton(state, scope)
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
                                clipboard.setText(AnnotatedString(pageUrl))
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
            }
            installed?.version?.let {
                Text(
                    s.modPageInstalledVersion(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
            // Said under the button, where the click was. A mod that landed without
            // something it requires is installed and will not run, and this is the
            // only place on the page that would ever mention it.
            if (state.installMissing.isNotEmpty()) {
                Text(
                    s.modPageInstallMissing(state.installMissing.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.warnAccent,
                )
            }
        }
    }
    // The rule under the header, which the reference draws and this did not: the
    // header and the body card were two floating blocks with nothing saying they
    // belonged to one page.
    HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
    }
}

/** Measured off the reference: a 24 title set tight, a 704 reading measure, a 96 mark. */
private val TITLE_SIZE = 24.sp
private val SUMMARY_MEASURE = 704.dp
private val ICON_SIZE = 96.dp

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
            onClick = { scope.launch { state.installIntoPack() } },
            icon = if (state.installFailed) NxIcon.Refresh else NxIcon.Download,
            enabled = !state.installing,
        )
    }
}

@Composable
private fun ProjectIcon(url: String?, title: String) {
    val shape = RoundedCornerShape(18.dp)
    // 96, as the reference sets it. 84 was mine and left the header looking like a
    // list row that had been enlarged rather than like the top of a page.
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ICON_SIZE).clip(shape),
        )
    } else {
        Box(
            Modifier.size(ICON_SIZE).clip(shape).background(NxTheme.colors.decorativeColor(title)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Stat(icon: IconKey, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Symbol(icon, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 15.dp)
        Text(value, style = MaterialTheme.typography.labelLarge, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
    }
}

/**
 * The page's tabs.
 *
 * Only the description is reachable so far: versions and the gallery are the next
 * two panes and are drawn disabled rather than left out, because a tab row that
 * grows later moves everything under it, and a reader who can see where the other
 * two will be is not surprised when they arrive.
 */
@Composable
internal fun Tabs(
    active: ModPageTab,
    onSelect: (ModPageTab) -> Unit,
    modifier: Modifier = Modifier,
    hasGallery: Boolean = false,
) {
    val s = LocalStrings.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // The gallery appears only where there are shots, the way the reference
        // does it. A tab that opens an empty pane is a click that tells a reader
        // nothing its absence would not have told them.
        val tabs = buildList {
            add(ModPageTab.Description to s.modPageTabDescription)
            add(ModPageTab.Versions to s.modPageTabVersions)
            add(ModPageTab.Changelog to s.modPageTabChangelog)
            if (hasGallery) add(ModPageTab.Gallery to s.modPageTabGallery)
        }
        tabs.forEach { (id, label) ->
            val selected = id == active
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(id) },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier.size(width = 26.dp, height = 2.dp)
                        .background(if (selected) NxTheme.colors.primary else Color.Transparent),
                )
            }
        }
    }
}
