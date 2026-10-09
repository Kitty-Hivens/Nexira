package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import hivens.ui.nx.NxIconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.core.api.interfaces.IPackRepository
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.feature.catalogue.project.ModTarget
import java.nio.file.Path
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Dimens
import hivens.ui.theme.Motion
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

/**
 * Browse = the catalogue of everything installable: packs from every registered
 * source, and mods, resource packs and shaders from Modrinth.
 *
 * The screen is a widget surface and owns none of its controls. What is searched,
 * in what order and into which pack is chosen in the right rail's browse family;
 * the search box and the results are this surface's two slots. All of them meet at
 * [BrowseController], so a widget moved, removed or added in the editor needs
 * nothing from its neighbours.
 *
 * What the screen does own is the work none of the widgets should: switching the
 * rail to its family for as long as Browse is up, and reading the chosen pack off
 * disk so every widget asks the same answer.
 */
@Composable
fun BrowseScreen(
    onOpenPack: (CataloguePack) -> Unit,
    /** Opens a project's page. Carries the target pack, when there is one, so the page can install into it. */
    onOpenProject: (ModTarget) -> Unit = {},
    /**
     * The pack this browse was opened from. It becomes the install target, and the
     * search moves to a kind the pack takes each time the reader asks for this browse
     * (see [BrowseController.aimAt]): a reader who then picks something else keeps it.
     */
    intoInstanceId: String? = null,
) {
    PuppetScreen(if (intoInstanceId == null) "Browse" else "BrowseInto")

    val c: BrowseController = koinInject()
    val repo: IPackRepository = koinInject()
    val dataDir: Path = koinInject()
    val families = LocalSurfaceFamilies.current

    // This visit, as the family's owner, so a page leaving over it hands the rail
    // back to Browse rather than to whatever it showed itself.
    val railOwner = remember { Any() }
    DisposableEffect(railOwner) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.BROWSE, railOwner)
        onDispose { families.reset(RIGHT_RAIL_SURFACE, railOwner) }
    }

    // The pack is the screen's own subject, so it is the target whenever the screen
    // comes up. The kind moves only when the reader asked for this browse
    // ([BrowseController.aimAt]): a flag kept with the screen's saved state outlived
    // the visit, so a second ask found it spent and stayed on the last kind, and a
    // remembered one undid the reader's pick on every return from a project page.
    LaunchedEffect(intoInstanceId) {
        if (intoInstanceId != null) c.targetId = intoInstanceId
    }
    LaunchedEffect(c.targetId) {
        val id = c.targetId ?: return@LaunchedEffect run { c.resolved = null }
        val pack = try {
            withContext(Dispatchers.IO) { repo.get(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable is not gone. The choice stays and the rows install into no
            // pack until it can be read, rather than quietly forgetting it. The reading
            // from before is dropped with it: kept, it answered for a pack whose game
            // version may have moved since.
            log.warn("reading the install target {} failed", id, e)
            c.resolved = null
            return@LaunchedEffect
        }
        if (pack == null) {
            // Deleted while chosen. Nothing can go into it any more.
            c.targetId = null
            return@LaunchedEffect
        }
        val target = withContext(Dispatchers.IO) { browseDestination(pack, dataDir).let { BrowseTarget(it, kindsFor(it)) } }
        c.resolved = target
        if (c.aimingAt == id) {
            if (!target.takes(c.kind)) target.kinds.firstOrNull()?.let { c.kind = it }
            c.aimingAt = null
        }
    }

    val ctx = remember(onOpenPack, onOpenProject) { BrowseContext(onOpenPack, onOpenProject) }
    CompositionLocalProvider(LocalBrowseContext provides ctx) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Past a point the extra width of a wide monitor stops being room and
            // starts stretching the search field and the cards.
            Column(
                Modifier.fillMaxHeight()
                    .widthIn(max = Dimens.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                SlotRenderer(SurfaceId(BROWSE_SURFACE), SlotId("header"), Modifier.fillMaxWidth(), spacing = 8.dp)
                SlotRenderer(SurfaceId(BROWSE_SURFACE), SlotId("body"), Modifier.weight(1f).fillMaxWidth(), spacing = 8.dp)
            }
        }
    }
}

private val log = LoggerFactory.getLogger("Browse")

internal const val BROWSE_SURFACE = "browse"

/** The source packs are listed from: the one picked, else the first registered. */
internal fun activeOrigin(picked: PackOrigin?, origins: List<PackOrigin>): PackOrigin =
    picked?.takeIf { it in origins } ?: origins.firstOrNull() ?: PackOrigin.Mirror

/**
 * Packs from one registered catalogue: the paged list for [origin] and [query].
 *
 * The source and the words come from outside, so this is only the list and the
 * work behind it: what was shown last, what the source says now, and the pages
 * after the first.
 */
@Composable
internal fun PackBrowse(
    origin: PackOrigin,
    query: String,
    onOpenPack: (CataloguePack) -> Unit,
    /**
     * Which of the listed packs are drawn, for a choice the source does not answer
     * itself. The list underneath is kept whole, so taking the choice back shows
     * what it hid without asking the source again.
     */
    shown: (CataloguePack) -> Boolean = { true },
) {
    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val session: BrowseSession = koinInject()
    val imageContext = LocalPlatformContext.current
    var submittedQuery by remember { mutableStateOf(query) }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.retry") { retryTick++ }

    // Debounce typing so each keystroke does not hit the network.
    LaunchedEffect(query) {
        delay(350.milliseconds)
        submittedQuery = query
    }

    // Paging state. The catalogue takes a page and only some of them honour it:
    // the mirror answers with its whole list every time. So a page is accepted by
    // what is NEW in it, and a page that adds nothing is the end -- which is
    // correct for a catalogue that pages and for one that does not, and keeps a
    // repeat from reaching the list as a duplicate key.
    // Keyed on the question being asked, all of them. Unkeyed, a request still in
    // flight when the source changes came back and wrote its answer under the new
    // source's name, and a flag left true by a cancelled load stayed true for the
    // life of the screen.
    var page by remember(origin, submittedQuery, retryTick) { mutableIntStateOf(0) }
    var endReached by remember(origin, submittedQuery, retryTick) { mutableStateOf(false) }
    var loadingMore by remember(origin, submittedQuery, retryTick) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // What this source and query were last showing goes up first, before anything
    // is asked of the catalogue. A spinner belongs over an empty screen, not over
    // a list the user was reading a moment ago and is about to get back nearly
    // unchanged -- which is every flip of the source switcher and every trip out
    // of the screen and back.
    // Every outcome of a browse is written down. None of them were: the catalogue
    // path holds no logger at all, and this effect turned a failure into UI state
    // and nothing else -- so a catalogue that stopped working left no line in the
    // log or in the diagnostic bundle, and the swallowed branch below left no trace
    // anywhere at all. The client throws with the status and the body, so what the
    // source actually answered is in the message.
    LaunchedEffect(origin, submittedQuery, retryTick) {
        val remembered = session.get(origin, submittedQuery)
        // Where a browse got to, not only how it ended. A screen stuck on its
        // spinner produced no line at all, so there was no way to tell an effect
        // that never started from one waiting on a source that never answered.
        log.info(
            "browse: asking {} for \"{}\", {}",
            origin,
            submittedQuery,
            remembered?.let { "showing ${it.packs.size} remembered pack(s) meanwhile" } ?: "nothing remembered",
        )
        // Both scrolls are REQUESTED, not awaited. scrollToItem suspends until an
        // attached, laid-out list has carried it out -- and the line above it puts
        // the screen on a state that composes no list at all, so in the branch that
        // has nothing to restore it never returned. Everything below is downstream
        // of it, which is why a catalogue that could not be shown made no request,
        // logged nothing, timed out on nothing and held no thread: the fetch was
        // parked behind a scroll that could not happen. requestScrollToItem records
        // the position and the next layout applies it, list or no list.
        if (remembered != null) {
            state = BrowseState.Loaded(remembered.packs)
            page = remembered.nextPage
            endReached = remembered.endReached
            listState.requestScrollToItem(remembered.firstVisibleIndex, remembered.firstVisibleOffset)
        } else {
            state = BrowseState.Loading
            page = 0
            endReached = false
            // A different question deserves the top of its answer. The scroll
            // state is one object across every query and source, so without this
            // a search made while scrolled opens at whatever offset the previous
            // list had reached -- past the end of a shorter one, which the paging
            // watcher then reads as "near the bottom" and pages on.
            listState.requestScrollToItem(0)
        }
        val catalogue = registry.forOrigin(origin)
            ?: return@LaunchedEffect run {
                log.warn("browse: no catalogue is registered for {}", origin)
                if (state !is BrowseState.Loaded) state = BrowseState.Empty
            }
        // A source that answers with its whole listing has no further pages, and
        // asking it for one only returns the same list again.
        if (!catalogue.paged) endReached = true
        try {
            // Stale first, fresh behind it. Assigning an equal list is not a
            // repaint -- the state is compared, not trusted -- so a refresh that
            // found nothing new costs the screen nothing.
            catalogue.searchStream(submittedQuery, page = 0)
                .flowOn(Dispatchers.IO)
                .collect { packs ->
                    // An empty answer is an answer. Keeping a restored list over
                    // it left packs on screen that the source had stopped
                    // listing, with nothing saying so. The put FORGETS rather than
                    // stores -- the session refuses an empty snapshot -- so the
                    // screen shows the empty state, with its retry, and the next
                    // entry asks again instead of restoring the blank.
                    if (packs.isEmpty()) {
                        // Answered, with nothing in it. Recorded separately from a
                        // failure because that is the distinction a report needs and
                        // the two look identical on screen.
                        log.info("browse: {} listed no packs for query \"{}\"", origin, submittedQuery)
                        state = BrowseState.Empty
                        session.put(origin, submittedQuery, BrowseSession.Snapshot(emptyList(), nextPage = 0, endReached = true))
                        return@collect
                    }
                    // The fresh page is compared against the front of what is
                    // shown, not swapped in over it. Unchanged is the ordinary
                    // answer, and there the pages scrolled on top still follow
                    // from it and must survive -- replacing the list wholesale
                    // would take a player back to the first twenty results a
                    // second after they scrolled past them.
                    // Membership, not order. A catalogue re-ranks constantly, and
                    // treating a shuffled first page as new content threw away
                    // every page scrolled onto the end of it -- and overwrote the
                    // remembered depth with zero, so leaving and returning could
                    // not get them back either. Only entries the list does not
                    // already hold are a reason to start again.
                    val shown = (state as? BrowseState.Loaded)?.packs
                    // A source with no pages hands over the entire list each time,
                    // so its answer IS the list: a pack published or withdrawn, or a
                    // card reworded, replaces it where it stands. Nothing was scrolled
                    // onto it to lose, and the reader is not sent back to the top.
                    if (!catalogue.paged) {
                        if (packs == shown) {
                            log.debug("browse: {} re-listed the same {} pack(s)", origin, packs.size)
                            return@collect
                        }
                        log.info("browse: {} listed {} pack(s)", origin, packs.size)
                        state = BrowseState.Loaded(packs)
                        session.put(
                            origin,
                            submittedQuery,
                            BrowseSession.Snapshot(
                                packs = packs,
                                nextPage = 0,
                                endReached = true,
                                firstVisibleIndex = listState.firstVisibleItemIndex,
                                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                            ),
                        )
                        return@collect
                    }
                    if (shown != null && newIn(packs, shown).isEmpty()) {
                        log.info("browse: {} re-listed the same {} pack(s)", origin, packs.size)
                        return@collect
                    }
                    log.info("browse: {} listed {} pack(s)", origin, packs.size)
                    page = 0
                    endReached = false
                    state = BrowseState.Loaded(packs)
                    session.put(origin, submittedQuery, BrowseSession.Snapshot(packs, nextPage = 0, endReached = false))
                    // Requested for the same reason as the two above: the list this
                    // would scroll is composed by the line before it, and awaiting a
                    // scroll from inside the collect would hold the rest of the
                    // stream behind it.
                    listState.requestScrollToItem(0)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged before it is decided what to show, because the branch that shows
            // nothing is the one that most needs a record.
            log.warn("browse: {} failed for query \"{}\"", origin, submittedQuery, e)
            // A source that failed while something of its own is on screen keeps
            // showing it. Replacing a readable list with an error page loses more
            // than the error explains.
            if (state !is BrowseState.Loaded) state = BrowseState.Error(e.message ?: s.browseErrorMessage)
        }
    }

    // Both images of every card on the page, resolved as the page lands rather
    // than as a card scrolls into view. They are all going to be fetched anyway
    // -- the list is already paged -- and fetching them on sight is what makes a
    // card change under the eye a moment after it is read.
    var prefetched by remember(origin, submittedQuery, retryTick) { mutableStateOf(0) }
    LaunchedEffect(state) {
        val packs = (state as? BrowseState.Loaded)?.packs ?: return@LaunchedEffect
        // Only what the page just added. Re-enqueueing the whole list on every
        // growth asks the loader for page one's images again on page five.
        if (packs.size <= prefetched) return@LaunchedEffect
        prefetchCardArt(imageContext, packs.subList(prefetched, packs.size))
        prefetched = packs.size
    }

    // Only the first page was ever asked for, so a catalogue with more to give
    // simply stopped at twenty results with nothing saying there were more.
    LaunchedEffect(listState, state, endReached) {
        if (state !is BrowseState.Loaded || endReached) return@LaunchedEffect
        // The question this effect is answering, taken once. The request suspends,
        // and reading the live source and query when it returns is how an answer
        // to the previous question got written under the name of the current one.
        val forOrigin = origin
        val forQuery = submittedQuery
        // A source that is not registered has no pages, which is not the same as
        // having reached the last of them: the flag suppresses every later attempt,
        // and the state that put it there is not one a scroll can leave.
        val catalogue = registry.forOrigin(forOrigin) ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val current = (state as? BrowseState.Loaded)?.packs ?: return@collect
                if (loadingMore || endReached || last < current.size - 3) return@collect
                loadingMore = true
                try {
                    val next = withContext(Dispatchers.IO) { catalogue.search(forQuery, page = page + 1) }
                    val fresh = newIn(next, current)
                    if (fresh.isEmpty()) endReached = true else {
                        page += 1
                        val grown = current + fresh
                        state = BrowseState.Loaded(grown)
                        session.put(
                            forOrigin,
                            forQuery,
                            BrowseSession.Snapshot(
                                packs = grown,
                                nextPage = page,
                                endReached = false,
                                firstVisibleIndex = listState.firstVisibleItemIndex,
                                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    // Leaving the screen is not a failure and must not be swallowed:
                    // a cancellation caught here would leave the coroutine running
                    // on past the disposal that asked it to stop.
                    throw e
                } catch (_: Exception) {
                    // A failure is not an ending. Marking the listing finished on
                    // one refused request means a moment without a network takes
                    // the rest of the catalogue away until the query is retyped.
                    // The next scroll asks again.
                } finally {
                    loadingMore = false
                }
            }
    }

    // Where the reader had got to, kept with the list it belongs to. Written on
    // the way out rather than on every scroll: the position only matters to a
    // return, and a write per frame of scrolling is a write per frame.
    //
    // Keyed on everything the cursor is keyed on. A retry makes page and endReached
    // new states, and an effect that outlived it went on reading the old ones, so a
    // retry followed by paging was put away with the new list and the old cursor.
    DisposableEffect(origin, submittedQuery, retryTick) {
        val forOrigin = origin
        val forQuery = submittedQuery
        onDispose {
            val packs = (state as? BrowseState.Loaded)?.packs ?: return@onDispose
            session.put(
                forOrigin,
                forQuery,
                BrowseSession.Snapshot(
                    packs = packs,
                    nextPage = page,
                    endReached = endReached,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }

    when (val st = state) {
        BrowseState.Loading -> BrowseLoading()
        BrowseState.Empty   -> BrowseEmpty(onRetry = { retryTick++ })
        is BrowseState.Error -> BrowseError(message = st.message, onRetry = { retryTick++ })
        is BrowseState.Loaded -> {
            val visible = st.packs.filter(shown)
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.contentEmpty, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                }
            } else {
                BrowseList(packs = visible, listState = listState, onOpenPack = onOpenPack)
            }
        }
    }
}

/**
 * Warms the image cache for a whole page of cards.
 *
 * A request with no target still runs and still lands in the loader's cache, so
 * the card that composes later finds its icon and its banner already decoded and
 * draws them on its first frame instead of fading them in over a placeholder.
 */
private fun prefetchCardArt(context: PlatformContext, packs: List<CataloguePack>) {
    val loader = SingletonImageLoader.get(context)
    packs.forEach { pack ->
        listOfNotNull(pack.iconUrl, pack.bannerUrl).forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/**
 * The search box: a filled field with the glass on the left and [trailing] at its
 * far end, inside the field, where a control that narrows the same question sits.
 *
 * It answers the pointer and the caret: the edge warms under the pointer and takes
 * the accent while the field has focus, so where typing will land is never a guess.
 */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val edge by animateColorAsState(
        when {
            focused -> NxColor.lead()
            hovered -> NxColor.wash(NxInk.main, 0.30f)
            else -> NxInk.line
        },
        animationSpec = Motion.colorShift.of(),
        label = "searchEdge",
    )
    val glass by animateColorAsState(if (focused) NxColor.lead(text = true) else NxInk.quiet, animationSpec = Motion.colorShift.of(), label = "searchGlass")
    NxSurface(
        SurfaceKind.Field,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        borderWidthDp = if (focused) 1.5f else 1f,
        borderColor = edge,
    ) {
        // The trailing control sits beside the text field and not inside it. Inside,
        // it was part of what the field answers: the pointer on the sort pill lit
        // the field as if typing would land there, and a press on it could hand the
        // field the caret.
        Row(
            Modifier.fillMaxWidth().padding(end = if (trailing != null) 6.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodyLarge.copy(color = NxInk.main),
                cursorBrush   = SolidColor(NxColor.lead()),
                modifier      = Modifier.weight(1f).hoverable(interaction).focusRequester(field).onFocusChanged { focused = it.isFocused },
            ) { inner ->
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = if (trailing != null) 0.dp else 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(
                        NxIcon.Search,
                        contentDescription = null,
                        tint               = glass,
                        modifier           = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                text  = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = NxInk.quiet,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                }
            }
            // Clearing is one click, the way the catalogue's own field does it, and
            // the caret goes back to the field: the press handed focus to a button
            // that the clear then removes, and the next keys went nowhere.
            AnimatedVisibility(visible = value.isNotEmpty(), enter = Motion.tap.enter, exit = Motion.tap.exit) {
                NxIconButton(icon = NxIcon.Close, contentDescription = null, onClick = {
                    onValueChange("")
                    field.requestFocus()
                })
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
    }
}

internal fun originLabel(origin: PackOrigin): String = when (origin) {
    PackOrigin.Mirror -> "Hivens"
    PackOrigin.Modrinth -> "Modrinth"
    PackOrigin.Smartycraft -> "SmartyCraft"
    PackOrigin.Local -> "Local"
    PackOrigin.Unknown -> "Other"
}

@Composable
internal fun BrowseLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = NxColor.wash(NxColor.lead(), 0.55f),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun BrowseEmpty(onRetry: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text       = s.browseEmptyTitle,
                style      = MaterialTheme.typography.titleLarge,
                color      = NxInk.main,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = s.browseEmptyMessage,
                style     = MaterialTheme.typography.bodyMedium,
                color     = NxInk.quiet,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 420.dp),
            )
            NxButton(label = s.browseRetry, onClick = onRetry)
        }
    }
}

@Composable
private fun BrowseError(message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    RetryStateBlock(
        title      = s.browseErrorTitle,
        message    = message,
        retryLabel = s.browseRetry,
        onRetry    = onRetry,
        modifier   = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BrowseList(
    packs: List<CataloguePack>,
    listState: LazyListState,
    onOpenPack: (CataloguePack) -> Unit,
) {
    LazyColumn(
        state               = listState,
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = packs, key = { packKey(it) }) { pack ->
            BrowsePackCard(
                pack = pack,
                onClick = { onOpenPack(pack) },
                modifier = Modifier.animateItem(
                    fadeInSpec    = Motion.fade,
                    placementSpec = Motion.reveal.of<IntOffset>(),
                    fadeOutSpec   = Motion.fade,
                ),
            )
            PuppetClick("browse.open.${pack.origin}.${pack.id}") { onOpenPack(pack) }
        }
    }
}

/**
 * The entries of [page] that are not already in [shown], in the page's own order.
 *
 * A catalogue takes a page number and only some of them honour it -- the mirror
 * answers with its whole listing every time -- so a page is accepted by what is
 * new in it, and a page that adds nothing is the end. That reading is correct for
 * a source that pages and for one that does not, and it keeps a repeat from
 * reaching a keyed list twice.
 *
 * Named, and tested, because it was written inline once and the identity it
 * compared by was a constant: every entry answered "already seen", every page
 * after the first was empty, and the list stopped at twenty with nothing saying
 * so.
 */
internal fun newIn(page: List<CataloguePack>, shown: List<CataloguePack>): List<CataloguePack> {
    val seen = shown.mapTo(HashSet(shown.size)) { packKey(it) }
    return page.filterNot { packKey(it) in seen }
}

/** A pack's identity across sources: two catalogues may both have an id "1". */
internal fun packKey(pack: CataloguePack): String = "${pack.origin}:${pack.id}"

sealed class BrowseState {
    object Loading : BrowseState()
    object Empty   : BrowseState()
    data class Loaded(val packs: List<CataloguePack>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
