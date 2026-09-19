package hivens.ui.screens.mod

import androidx.compose.foundation.background
import hivens.ui.theme.Motion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import hivens.ui.nx.NxSteadyText
import hivens.ui.nx.RetryStateBlock
import hivens.ui.components.ImageGallery
import hivens.ui.components.modrinthGalleryMedia
import hivens.ui.render.MarkdownHtml
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
import java.awt.datatransfer.StringSelection

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
    val families = LocalSurfaceFamilies.current
    val s = LocalStrings.current

    val state = remember(target) {
        ModDetailState(target, modrinth, repo, dataDir, scanner, openProject, strings = s)
    }
    var reloadTick by remember(state) { mutableStateOf(0) }
    // Saveable, not remembered. The only way to a build's page is the Versions
    // tab, and the shell keeps a screen's saveable state across a visit -- so
    // without this, Back from a build always landed on Description, which is not
    // the tab anybody left from.
    var tab by rememberSaveable(
        state,
        stateSaver = Saver(save = { it.name }, restore = { ModPageTab.valueOf(it) }),
    ) { mutableStateOf(ModPageTab.Description) }
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
        Header(state)
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
                ModPageTab.Versions -> VersionsPane(
                    state = state,
                    onOpenVersion = { v -> onOpenVersion(v.id, v.versionNumber.ifBlank { v.name }) },
                    onReload = { reloadTick++ },
                    modifier = Modifier.fillMaxSize(),
                )
                ModPageTab.Changelog -> ChangelogPane(state, onReload = { reloadTick++ }, modifier = Modifier.fillMaxSize())
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

@Composable
internal fun Body(state: ModDetailState, onRetry: () -> Unit) {
    val s = LocalStrings.current
    val follow = rememberLinkFollower()
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
    color = NxTheme.colors.textSecondary,
)

// ClipEntry is still experimental; the console's copy actions carry the same
// opt-in for the same call.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Header(state: ModDetailState) {
    val scope = rememberCoroutineScope()
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
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
                // Whether an install is possible at all is known from the route.
                // WHAT goes in the slot needs the pack read off disk, and until it
                // lands [InstallAction.None] draws nothing, so this reserves the
                // fact rather than the width: the button still appears a moment in.
                // Reserving the width would mean knowing the label, and the label
                // names the pack.
                if (state.installPossible) {
                    Box(contentAlignment = Alignment.Center) { InstallButton(state, scope) }
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
            }
            installed?.version?.let {
                Text(
                    s.modPageInstalledVersion(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
            // Why nothing happened, under the button that was pressed, naming the
            // two things that decided it. Without this the click did nothing and
            // said nothing, which reads as the launcher having ignored it.
            if (state.installNoBuild) {
                // Names the axes that are actually KNOWN. Filling a blank one with
                // the unknown placeholder produced "no build for Unknown / Unknown",
                // which is a sentence about our own ignorance rather than about
                // the pack.
                val target = listOf(state.packMcVersion, state.packLoaders.firstOrNull().orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                Text(
                    if (target.isBlank()) s.modPageNoBuildAny else s.modPageNoBuildFor(target),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.warnAccent,
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
    val density = LocalDensity.current
    // The gallery appears only where there are shots, the way the reference does
    // it. A tab that opens an empty pane is a click that tells a reader nothing
    // its absence would not have told them.
    val tabs = buildList {
        add(ModPageTab.Description to s.modPageTabDescription)
        add(ModPageTab.Versions to s.modPageTabVersions)
        add(ModPageTab.Changelog to s.modPageTabChangelog)
        if (hasGallery) add(ModPageTab.Gallery to s.modPageTabGallery)
    }

    // Where each tab's mark belongs, measured rather than computed: the labels are
    // five different words in five languages and the row is the only thing that
    // knows how wide each came out.
    val marks = remember { mutableStateMapOf<ModPageTab, Dp>() }
    val target = marks[active]
    val travel = remember { Animatable(0.dp, Dp.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    val spec = Motion.track.of<Dp>()
    LaunchedEffect(target, spec) {
        val to = target ?: return@LaunchedEffect
        if (!placed) {
            // The first position is where the mark ALREADY is. Animating to it
            // would slide the underline in from the left edge on arrival, as if
            // the reader had just moved it there.
            travel.snapTo(to)
            placed = true
        } else {
            travel.animateTo(to, spec)
        }
    }

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            tabs.forEach { (id, label) ->
                // Measured at its heaviest, drawn at its current weight. A bold face
                // is wider, so selecting a tab used to widen its label and slide
                // every tab after it sideways, out from under the cursor that had
                // just clicked.
                NxSteadyText(
                    text = label,
                    weight = if (id == active) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (id == active) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary,
                    // No indication. A tab already says where you are with its weight
                    // and its rule, and a hover plate behind the word is a second
                    // answer to a question the row has already answered.
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(id) }
                        .onGloballyPositioned { c ->
                            val centre = c.positionInParent().x + c.size.width / 2f
                            marks[id] = with(density) { (centre - MARK_WIDTH.toPx() / 2f).toDp() }
                        },
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        // ONE mark that travels, not one per tab that blinks on and off. The
        // underline is the same object wherever it is, so it moves the way the
        // reader's attention does -- and because every click retargets an
        // animation already in flight, a run of fast clicks is followed rather
        // than queued: the mark is always heading for the tab last asked for,
        // from wherever it had got to.
        Box(Modifier.fillMaxWidth().height(MARK_HEIGHT)) {
            if (placed) {
                Box(
                    Modifier.offset(x = travel.value)
                        .size(width = MARK_WIDTH, height = MARK_HEIGHT)
                        .background(NxTheme.colors.primary),
                )
            }
        }
    }
}

/** The underline: a mark under the word rather than a rule the width of it. */
private val MARK_WIDTH = 26.dp
private val MARK_HEIGHT = 2.dp
