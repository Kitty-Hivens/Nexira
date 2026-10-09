package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.data.PackOrigin
import hivens.core.data.PackInstance
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.ContentInstaller
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ModInstaller
import hivens.launcher.instance.PackPlacedContent
import hivens.launcher.instance.takesFromPlayer
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.SearchFilter
import hivens.launcher.modrinth.acceptedLoaders
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.components.relativeAge
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTooltip
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.feature.catalogue.project.compactCount
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.theme.decorativeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("ProjectBrowse")

/** The pack a browse installs into, and what it runs. */
internal class BrowseDestination(val pack: PackInstance, val target: ContentDestination.Pack)

/** The destination for the pack [pack], its folder under [dataDir]. */
internal fun browseDestination(pack: PackInstance, dataDir: Path) =
    BrowseDestination(pack, ContentDestination.Pack.of(pack, dataDir.resolve("instances")))

/**
 * The kinds a pack takes from the player, in the order the rail lists them.
 *
 * A mirror pack never takes mods, and a pack with no loader runs none, so a row
 * offering to install one there would be offering something that cannot happen.
 */
internal fun kindsFor(destination: BrowseDestination?): List<ContentKind> {
    val all = listOf(ContentKind.Mod, ContentKind.ResourcePack, ContentKind.ShaderPack)
    val target = destination?.target ?: return all
    val keepsRecord = Files.isDirectory(target.dir) && PackPlacedContent.paths(target.dir) != null
    return all.filter { kind ->
        takesFromPlayer(target.origin, kind, keepsRecord) &&
            (kind != ContentKind.Mod || acceptedLoaders(target.loader, target.mcVersion).isNotEmpty())
    }
}

/** What a pack runs, as a reader would write it: "NeoForge 1.21.1". */
internal fun runtimeLabel(target: ContentDestination.Pack): String =
    listOf(target.loader.takeIf { it.isNotBlank() }?.let(::loaderLabel), target.mcVersion.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" ")

/** Why an install from a row did not land. */
internal sealed interface InstallProblem {
    /** No build of the project runs on the pack. */
    data object NoBuild : InstallProblem

    /** The install did not run, see [ContentInstaller.Refusal]. */
    data class Refused(val reason: ContentInstaller.Refusal) : InstallProblem

    /** It ran and the build that was asked for did not land. */
    data object NotLanded : InstallProblem

    /** It ran and left the build that was asked for out, for [skip]'s reason. */
    data class LeftOut(val skip: ContentInstaller.Skip) : InstallProblem
}

internal fun InstallProblem.label(s: AppStrings): String = when (this) {
    InstallProblem.NoBuild -> s.installTargetNoBuild
    is InstallProblem.Refused -> when (reason) {
        ContentInstaller.Refusal.GameRunning -> s.installRefusedGameRunning
        is ContentInstaller.Refusal.Busy -> s.installRefusedBusy
    }
    InstallProblem.NotLanded -> s.contentInstallFailed
    is InstallProblem.LeftOut -> skip.reason(s)
}

/** Why an install left a project out, in the reader's words. */
internal fun ContentInstaller.Skip.reason(s: AppStrings): String = when (this) {
    is ContentInstaller.Skip.NoBuild -> s.installTargetNoBuild
    is ContentInstaller.Skip.LookupFailed -> s.installSkipLookupFailed
    is ContentInstaller.Skip.NotPlaceable -> s.installSkipNotPlaceable
    is ContentInstaller.Skip.NotAllowed -> s.installTargetNotTaken
    is ContentInstaller.Skip.NameTaken -> s.installSkipNameTaken
    is ContentInstaller.Skip.Failed -> s.contentInstallFailed
    is ContentInstaller.Skip.NotAttempted -> s.installSkipNotAttempted
    is ContentInstaller.Skip.TooDeep -> s.installSkipTooDeep
    is ContentInstaller.Skip.PackOwned -> s.installSkipPackOwned
    is ContentInstaller.Skip.Present, is ContentInstaller.Skip.AlreadyInstalled -> ""
}

/** The problem an [outcome] reports, or null when what was asked for landed. */
internal fun problemOf(outcome: ModInstaller.Outcome): InstallProblem? {
    if (outcome.ok) return null
    outcome.refusal?.let { return InstallProblem.Refused(it) }
    return outcome.headLeftOut?.let { InstallProblem.LeftOut(it) } ?: InstallProblem.NotLanded
}

/**
 * One search of the catalogue: its pages of results, and the installs started
 * from them.
 *
 * With a [destination] every install goes into that pack, and what the pack
 * already holds is read before the first page, so a search that hides it can.
 * Without one a result only opens its page or the install dialog.
 *
 * What a search hides is the pack as it was when the search began. A project
 * installed from the list stays on it, reading installed, and the pages after it
 * are asked for with the same exclusion: one more excluded id moves every later
 * result up a place, and the page read next would start one result past where it
 * should.
 *
 * With a [session] a search already shown is shown again as it was left, see
 * [ProjectBrowseSession], and [keyOf] names a query's place in it.
 */
@Stable
internal class ProjectBrowseState(
    /** The kind searched, null for modpacks, which are not installed into a pack. */
    val kind: ContentKind?,
    val destination: BrowseDestination?,
    /**
     * One page of results for a query, from an offset in the catalogue's own order,
     * with [excluded] left out by the catalogue.
     */
    private val search: suspend (query: String, offset: Int, limit: Int, excluded: Collection<String>) -> List<ModrinthSearchHit>,
    /** Installs a result into the destination pack, or null when no build of it runs there. */
    private val installInto: suspend (ModrinthSearchHit) -> ModInstaller.Outcome?,
    /** The projects the destination pack already carries. */
    private val presentProjects: suspend () -> Set<String>,
    /** Whether what the pack already carries is left out of the results. */
    private val hideInstalled: Boolean = false,
    private val session: ProjectBrowseSession? = null,
    private val keyOf: (query: String) -> ProjectBrowseSession.Key? = { null },
) {
    /** Where the list was scrolled to when this search was last shown, for the list to start at. */
    var restoredScroll: Pair<Int, Int> = 0 to 0
        private set

    /** Null while the first page is in flight. */
    var results by mutableStateOf<List<ModrinthSearchHit>?>(null)
        private set

    /** The search could not be run. Not the same as a search that found nothing. */
    var searchFailed by mutableStateOf(false)
        private set

    private var endReached = false
    private var loadingMore = false
    private var asked: String? = null
    private var presentRead = false

    /** Which search is current. A page that arrives for an older one is dropped. */
    private var generation = 0

    /** Where the next page starts in the catalogue's order, which is not the shown count once some are hidden here. */
    private var offset = 0

    /** What this search leaves out, fixed when it began. */
    private var hidden: Set<String> = emptySet()

    /** Projects the destination pack already carries, read from its folders. */
    var present by mutableStateOf(emptySet<String>())
        private set

    var working by mutableStateOf(emptySet<String>())
        private set

    var problems by mutableStateOf(emptyMap<String, InstallProblem>())
        private set

    suspend fun loadPresent() {
        if (destination == null) return
        presentRead = true
        // A row reading Install for something already there is a wasted click; a
        // browser that refuses to open over a folder it cannot read is worse.
        present = present + runCatching { presentProjects() }
            .onFailure { if (it is CancellationException) throw it else log.warn("reading what the pack holds failed", it) }
            .getOrDefault(emptySet())
    }

    suspend fun search(q: String) {
        val gen = ++generation
        if (restore(q)) return
        asked = q
        results = null
        searchFailed = false
        endReached = false
        offset = 0
        if (!presentRead) loadPresent()
        if (gen != generation) return
        hidden = if (hideInstalled) present else emptySet()
        val first = nextBatch(q, gen, known = emptySet())
        if (gen != generation) return
        if (first == null) {
            searchFailed = true
            results = emptyList()
            return
        }
        results = first
        keep()
    }

    /**
     * Shows the search for [q] as it was left, when the session still holds it,
     * and answers whether it did. Nothing is asked of the catalogue or of the pack.
     */
    private fun restore(q: String): Boolean {
        val key = keyOf(q) ?: return false
        val snap = session?.get(key) ?: return false
        asked = q
        searchFailed = false
        endReached = snap.endReached
        offset = snap.offset
        hidden = snap.hidden
        present = present + snap.present
        presentRead = true
        restoredScroll = snap.firstVisibleIndex to snap.firstVisibleOffset
        results = snap.results
        return true
    }

    /** Writes where the search has got to into the session. */
    private fun keep() {
        val q = asked ?: return
        val shown = results ?: return
        if (searchFailed) return
        val key = keyOf(q) ?: return
        val s = session ?: return
        val scroll = s.get(key)?.let { it.firstVisibleIndex to it.firstVisibleOffset } ?: restoredScroll
        s.put(key, ProjectBrowseSession.Snapshot(shown, offset, endReached, hidden, present, scroll.first, scroll.second, s.now()))
    }

    /** Records where the reader has scrolled this search to, for a return to it. */
    fun rememberScroll(index: Int, offset: Int) {
        val key = asked?.let(keyOf) ?: return
        session?.scroll(key, index, offset)
    }

    /** The next page, when the list has one and none is already on its way. */
    suspend fun more() {
        val shown = results ?: return
        val q = asked ?: return
        if (endReached || loadingMore) return
        val gen = generation
        loadingMore = true
        try {
            // A page that could not be read is not the end: the next scroll asks again.
            val fresh = nextBatch(q, gen, known = shown.mapTo(HashSet()) { it.projectId }) ?: return
            if (gen == generation && fresh.isNotEmpty()) results = shown + fresh
            if (gen == generation) keep()
        } finally {
            loadingMore = false
        }
    }

    /**
     * The next results worth showing, reading on past a page that held nothing new.
     *
     * The catalogue is told to leave out at most [MAX_EXCLUDED] of [hidden], since
     * every one of them is a clause in the query, and the rest are dropped here. A
     * pack of a few hundred mods can then fill whole pages with what it already
     * has, so a page that comes back emptied is read past rather than taken as the
     * end. Null when a page could not be read.
     */
    private suspend fun nextBatch(q: String, gen: Int, known: Set<String>): List<ModrinthSearchHit>? {
        repeat(MAX_EMPTY_PAGES) {
            val page = fetch(q, offset) ?: return null
            if (gen != generation) return emptyList()
            offset += page.size
            if (page.size < PAGE_SIZE) endReached = true
            val fresh = page.filterNot { it.projectId in hidden || it.projectId in known }
            if (fresh.isNotEmpty() || endReached) return fresh
        }
        endReached = true
        return emptyList()
    }

    private suspend fun fetch(q: String, offset: Int): List<ModrinthSearchHit>? = try {
        search(q, offset, PAGE_SIZE, hidden.take(MAX_EXCLUDED))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("searching {} for \"{}\" failed", kind ?: MODPACK, q, e)
        null
    }

    /**
     * Install [hit] into the destination pack. A row reads installed only when the
     * build landed, and what the install reports the pack holds afterwards is taken
     * whole, so a dependency pulled in behind it stops offering itself too.
     */
    suspend fun install(hit: ModrinthSearchHit) {
        val id = hit.projectId
        working = working + id
        problems = problems - id
        val problem = try {
            val outcome = installInto(hit)
            if (outcome?.ok == true) present = present + outcome.present + id
            if (outcome == null) InstallProblem.NoBuild else problemOf(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("installing {} failed", id, e)
            InstallProblem.NotLanded
        } finally {
            working = working - id
        }
        if (problem != null) problems = problems + (id to problem)
        keep()
    }

    private companion object {
        const val PAGE_SIZE = 30

        /** Ids the catalogue is asked to leave out. The query carries each one, so it is bounded. */
        const val MAX_EXCLUDED = 200

        /** Pages read past in a row because everything on them was hidden, before the list ends. */
        const val MAX_EMPTY_PAGES = 4
    }
}

/**
 * A search of [type] narrowed by [filters], installing into [destination] when there
 * is one. A new search, with its own pages, whenever any of those or [sort] changes.
 * With [hideInstalled] what the pack already holds is left out by id.
 */
@Composable
internal fun rememberProjectBrowseState(
    type: String,
    kind: ContentKind?,
    destination: BrowseDestination?,
    sort: BrowseSort = BrowseSort.Relevance,
    filters: Set<SearchFilter> = emptySet(),
    hideInstalled: Boolean = false,
): ProjectBrowseState {
    val modrinth: ModrinthClient = koinInject()
    val installer: ModInstaller = koinInject()
    val session: ProjectBrowseSession = koinInject()
    val hiding = hideInstalled && destination != null
    return remember(type, destination?.pack?.id, sort, filters, hiding) {
        val target = destination?.target
        ProjectBrowseState(
            kind = kind,
            destination = destination,
            search = { q, offset, limit, excluded ->
                val hidden = excluded.map { SearchFilter(FilterField.Project, it, excluded = true) }
                withContext(Dispatchers.IO) {
                    modrinth.search(type, q, filters + hidden, offset, limit, sort.index).hits
                }
            },
            installInto = { hit ->
                target?.let { t ->
                    withContext(Dispatchers.IO) {
                        modrinth.newestMatchingVersion(hit.projectId, t.mcVersion, t.loader)
                            ?.let { installer.install(t.dir, it, t.mcVersion, t.loader) }
                    }
                }
            },
            presentProjects = {
                val dir = target?.dir
                if (dir == null) emptySet() else withContext(Dispatchers.IO) { installer.presentProjects(dir) }
            },
            hideInstalled = hiding,
            session = session,
            keyOf = { q -> ProjectBrowseSession.Key(type, q, sort, filters, destination?.pack?.id, hiding) },
        )
    }
}

/**
 * The catalogue's results for one project type.
 *
 * A card opens the project's page, or a modpack's. When [target] takes the kind
 * every card carries an install straight into it. Without one a card carries the
 * install that asks where, see [InstallDialog]. A modpack is not installed into a
 * pack, so its card is only a way to its page.
 */
@Composable
internal fun ProjectResults(
    type: String,
    kind: ContentKind?,
    query: String,
    sort: BrowseSort,
    target: BrowseTarget?,
    filters: Set<SearchFilter>,
    hideInstalled: Boolean,
    onOpenProject: (ModTarget) -> Unit,
    onOpenPack: (CataloguePack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val destination = target?.takeIf { it.takes(kind) }?.destination
    val state = rememberProjectBrowseState(type, kind, destination, sort, filters, hideInstalled)
    // Installs run on the app's scope: one fetches the clicked build and then what
    // it requires, and leaving the screen between the two left a mod without them.
    val installScope: CoroutineScope = koinInject()
    var submitted by remember(state) { mutableStateOf(query) }
    var retryTick by remember(state) { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<InstallSubject?>(null) }

    PuppetClick("browse.retry") { retryTick++ }
    LaunchedEffect(state, query) {
        delay(350.milliseconds)
        submitted = query
    }
    LaunchedEffect(state, submitted, retryTick) { state.search(submitted) }

    choosing?.let { subject -> InstallDialog(subject) { choosing = null } }

    val results = state.results
    when {
        results == null -> BrowseLoading()
        state.searchFailed -> RetryStateBlock(
            title      = s.modBrowserErrorTitle,
            message    = s.modBrowserErrorMessage,
            retryLabel = s.browseRetry,
            onRetry    = { retryTick++ },
            modifier   = modifier.fillMaxSize(),
        )
        results.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.contentEmpty, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
        }
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            // The catalogue's own break: a narrow column takes the small icon so the
            // title keeps its width.
            val icon = if (maxWidth >= WIDE_CARD) ICON_WIDE else ICON_NARROW
            // Starts where the reader left this search, and says where they leave it.
            val listState = rememberLazyListState(state.restoredScroll.first, state.restoredScroll.second)
            DisposableEffect(listState, state) {
                onDispose { state.rememberScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            }
            LaunchedEffect(listState, state) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .collect { last -> if (last >= (state.results?.size ?: 0) - 3) state.more() }
            }
            LazyColumn(
                state               = listState,
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(CARD_GAP),
            ) {
                items(items = results, key = { it.projectId }) { hit ->
                    val id = hit.projectId
                    val open = if (kind == null) {
                        { onOpenPack(modpackOf(hit)) }
                    } else {
                        { onOpenProject(ModTarget.Catalogue(id, destination?.pack?.id)) }
                    }
                    val subject = InstallSubject(id, hit.title, hit.iconUrl, hit.author)
                    val install = if (destination != null) {
                        { installScope.launch(Dispatchers.Main) { state.install(hit) }; Unit }
                    } else {
                        { choosing = subject }
                    }
                    val problem = state.problems[id]
                    PuppetClick("browse.project.$id") { open() }
                    if (kind != null) PuppetClick("browse.install.$id") { install() }
                    ProjectCard(
                        hit      = hit,
                        iconSize = icon,
                        note     = problem?.label(s),
                        onOpen   = open,
                        modifier = Modifier.animateItem(
                            fadeInSpec    = Motion.fade,
                            placementSpec = Motion.reveal.of<IntOffset>(),
                            fadeOutSpec   = Motion.fade,
                        ),
                        action   = if (kind == null) {
                            null
                        } else {
                            {
                                CardInstall(
                                    phase = when {
                                        id in state.present -> CardPhase.Installed
                                        id in state.working -> CardPhase.Working
                                        problem != null -> CardPhase.Failed
                                        else -> CardPhase.Offered
                                    },
                                    onInstall = install,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A catalogue modpack as the pack page opens it. */
private fun modpackOf(hit: ModrinthSearchHit) = CataloguePack(
    origin = PackOrigin.Modrinth,
    id = hit.projectId,
    title = hit.title,
    tagline = hit.description,
    iconUrl = hit.iconUrl,
    bannerUrl = hit.featuredGallery ?: hit.gallery.firstOrNull(),
    tags = hit.categories,
)

/** Where a card's install stands. */
internal enum class CardPhase { Offered, Working, Installed, Failed }

/**
 * A card's install, in the catalogue's outlined accent: offered, under way with a
 * spinner, done and disabled, or failed with the action offered again.
 *
 * One control changing what it says rather than four controls swapped in and out,
 * so the eye stays on the place it clicked. A failure keeps the action and the
 * card says why under its summary: silence after a click reading as success is the
 * failure this replaced.
 */
@Composable
internal fun CardInstall(phase: CardPhase, onInstall: () -> Unit) {
    val s = LocalStrings.current
    // Resolved here: a role is read in composition, and the transition is built outside it.
    val tap = Motion.tap
    AnimatedContent(
        targetState    = phase,
        transitionSpec = {
            (fadeIn(tap.of()) + scaleIn(tap.of(), initialScale = 0.9f)) togetherWith
                fadeOut(tap.of()) using SizeTransform(clip = false) { _, _ -> tap.of() }
        },
        contentAlignment = Alignment.CenterEnd,
        label          = "cardInstall",
    ) { p ->
        when (p) {
            CardPhase.Offered -> OutlinedAction(s.browseDetailInstallButton, NxColor.lead(), NxColor.lead(text = true), onClick = onInstall) {
                Symbol(NxIcon.Download, contentDescription = null, tint = NxColor.lead(text = true), size = ACTION_ICON)
            }
            CardPhase.Working -> OutlinedAction(s.modPageInstalling, NxInk.line, NxInk.quiet, onClick = null) {
                CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(ACTION_ICON - 2.dp))
            }
            CardPhase.Installed -> OutlinedAction(s.installInstalled, NxInk.line, NxInk.quiet, onClick = null) {
                Symbol(NxIcon.Check, contentDescription = null, tint = NxColor.lead(text = true), size = ACTION_ICON)
            }
            CardPhase.Failed -> OutlinedAction(s.contentInstallRetry, NxColor.status(Status.Error), NxColor.status(Status.Error, text = true), onClick = onInstall) {
                Symbol(NxIcon.Refresh, contentDescription = null, tint = NxColor.status(Status.Error, text = true), size = ACTION_ICON)
            }
        }
    }
}

/**
 * The outlined pill a card's action is drawn as: [edge] for the outline, [ink] for
 * the label. With no [onClick] it is a statement rather than a control, so the
 * outline dims and nothing answers the pointer.
 */
@Composable
private fun OutlinedAction(
    label: String,
    edge: Color,
    ink: Color,
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        targetValue   = if (hovered && onClick != null) NxColor.wash(edge, 0.14f) else Color.Transparent,
        animationSpec = Motion.tap.of(),
        label         = "actionFill",
    )
    Row(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, edge.copy(alpha = if (onClick != null) 0.85f else 0.5f), shape)
            .hoverable(interaction, enabled = onClick != null)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading()
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * One search result, laid out the way the catalogue lays its own: the mark on the
 * left, the name with its author and the summary beside it, the tags along the
 * bottom, and the numbers down the right edge under the action.
 *
 * [note] is said under the summary, for what went wrong with the card's install.
 */
@Composable
internal fun ProjectCard(
    hit: ModrinthSearchHit,
    modifier: Modifier = Modifier,
    iconSize: Dp = ICON_WIDE,
    note: String? = null,
    onOpen: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val s = LocalStrings.current
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The edge warms toward the accent under the pointer, which is how a card that
    // opens on click says so before it is clicked.
    val edge by animateColorAsState(
        targetValue   = if (hovered && onOpen != null) NxColor.wash(NxColor.lead(), 0.55f) else Color.Transparent,
        animationSpec = Motion.tap.of(),
        label         = "cardEdge",
    )
    NxSurface(
        SurfaceKind.Card,
        shape    = shape,
        modifier = modifier.fillMaxWidth().border(1.dp, edge, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .then(if (onOpen != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onOpen) else Modifier)
                .padding(CARD_PADDING),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectIcon(hit, iconSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        hit.title,
                        style      = MaterialTheme.typography.titleLarge,
                        fontSize   = TITLE,
                        color      = NxInk.main,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    if (hit.author.isNotBlank()) {
                        Text(
                            s.browseByAuthor(hit.author),
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = NxInk.quiet,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                if (hit.description.isNotBlank()) {
                    Text(
                        hit.description,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = NxInk.main,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = NxColor.status(Status.Error, text = true), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ProjectTags(hit.displayCategories.ifEmpty { hit.categories }, max = if (action != null) TAGS_WITH_ACTION else TAGS)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                action?.invoke()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat(NxIcon.Download, hit.downloads, s.modPageStatDownloads)
                    Stat(NxIcon.Favorite, hit.follows, s.modPageStatFollowers)
                }
                if (hit.dateModified.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(NxIcon.History, contentDescription = null, tint = NxInk.quiet, size = 16.dp)
                        Text(
                            s.modUpdatedOn(relativeAge(hit.dateModified, s)),
                            style    = MaterialTheme.typography.labelMedium,
                            color    = NxInk.quiet,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** A count with its mark, compact on the card and whole in the tooltip. */
@Composable
private fun Stat(icon: IconKey, n: Long, unit: String) {
    val s = LocalStrings.current
    NxTooltip(text = "%,d %s".format(n, unit)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(icon, contentDescription = null, tint = NxInk.quiet, size = STAT_ICON)
            Text(compactCount(n, s), style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The catalogue's tags for a card, loaders with their own mark, then a count of
 * the rest. One line: a chip is placed at its own width or not at all.
 */
@Composable
private fun ProjectTags(tags: List<String>, max: Int) {
    if (tags.isEmpty()) return
    val s = LocalStrings.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines              = 1,
    ) {
        tags.take(max).forEach { tag ->
            val loader = hasLoaderGlyph(tag)
            NxMetaChip(
                if (loader) loaderLabel(tag) else s.modrinthCategory(tag),
                tone    = NxMetaChipTone.Surface,
                leading = if (loader) {
                    { LoaderGlyph(tag, tint = NxInk.quiet, size = 12.dp) }
                } else {
                    null
                },
            )
        }
        val hidden = tags.size - max
        if (hidden > 0) NxMetaChip("+$hidden", tone = NxMetaChipTone.Surface)
    }
}

@Composable
private fun ProjectIcon(hit: ModrinthSearchHit, size: Dp) {
    val shape = MaterialTheme.shapes.medium
    if (hit.iconUrl != null) {
        AsyncImage(model = hit.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(decorativeColor(hit.title)), contentAlignment = Alignment.Center) {
            Text(
                hit.title.firstOrNull()?.uppercase() ?: "?",
                style      = if (size >= ICON_WIDE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The card's measurements, taken off the catalogue's own list: sixteen inside, a
 * hundred for the mark and sixty-four below the width where the column cannot
 * spare it, twenty for the name.
 */
private val CARD_PADDING = 16.dp
private val CARD_GAP = 10.dp
internal val ICON_WIDE = 100.dp
internal val ICON_NARROW = 64.dp
private val WIDE_CARD = 850.dp
private val TITLE = 20.sp
private val STAT_ICON = 20.dp
private val ACTION_ICON = 16.dp

/** Tags named before counting: one fewer when the action takes part of the width. */
private const val TAGS = 5
private const val TAGS_WITH_ACTION = 4
