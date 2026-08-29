package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Browse = the catalogue of everything installable, across sources. A source
 * switcher (Hivens mirror / Modrinth) drives which [hivens.core.api.interfaces.IPackCatalogueService]
 * the search + grid read from; the search box queries the active source
 * (Modrinth searches its catalogue, the mirror filters its listing client-side).
 * Clicking a card opens the source's detail screen. Importing a local pack lives
 * in Library (it adds to the collection), not here.
 */
@Composable
fun BrowseScreen(
    onOpenPack: (CataloguePack) -> Unit,
) {
    PuppetScreen("Browse")

    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val origins = registry.origins

    val session: BrowseSession = koinInject()
    val imageContext = LocalPlatformContext.current
    // Seeded from the session, so returning from a pack comes back to the source
    // that pack was found on. A remembered source that is no longer registered
    // falls back to the first, rather than selecting a tab that is not there.
    var origin by remember {
        mutableStateOf(session.origin?.takeIf { it in origins } ?: origins.firstOrNull() ?: PackOrigin.Mirror)
    }
    LaunchedEffect(origin) { session.origin = origin }
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.retry") { retryTick++ }
    PuppetField("browse.search", query) { query = it }

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
    val logger = remember { LoggerFactory.getLogger("Browse") }

    LaunchedEffect(origin, submittedQuery, retryTick) {
        val remembered = session.get(origin, submittedQuery)
        // Where a browse got to, not only how it ended. A screen stuck on its
        // spinner produced no line at all, so there was no way to tell an effect
        // that never started from one waiting on a source that never answered.
        logger.info(
            "browse: asking {} for \"{}\" (restored={} packs)",
            origin, submittedQuery, remembered?.packs?.size ?: -1,
        )
        if (remembered != null) {
            state = BrowseState.Loaded(remembered.packs)
            page = remembered.nextPage
            endReached = remembered.endReached
            listState.scrollToItem(remembered.firstVisibleIndex, remembered.firstVisibleOffset)
        } else {
            state = BrowseState.Loading
            page = 0
            endReached = false
            // A different question deserves the top of its answer. The scroll
            // state is one object across every query and source, so without this
            // a search made while scrolled opens at whatever offset the previous
            // list had reached -- past the end of a shorter one, which the paging
            // watcher then reads as "near the bottom" and pages on.
            listState.scrollToItem(0)
        }
        val catalogue = registry.forOrigin(origin)
            ?: return@LaunchedEffect run {
                logger.warn("browse: no catalogue is registered for {}", origin)
                if (state !is BrowseState.Loaded) state = BrowseState.Empty
            }
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
                        logger.info("browse: {} listed no packs for query \"{}\"", origin, submittedQuery)
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
                    if (shown != null && newIn(packs, shown).isEmpty()) {
                        logger.info("browse: {} re-listed the same {} pack(s)", origin, packs.size)
                        return@collect
                    }
                    logger.info("browse: {} listed {} pack(s)", origin, packs.size)
                    page = 0
                    endReached = false
                    state = BrowseState.Loaded(packs)
                    session.put(origin, submittedQuery, BrowseSession.Snapshot(packs, nextPage = 0, endReached = false))
                    listState.scrollToItem(0)
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged before it is decided what to show, because the branch that shows
            // nothing is the one that most needs a record.
            logger.warn("browse: {} failed for query \"{}\"", origin, submittedQuery, e)
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
                } catch (e: kotlinx.coroutines.CancellationException) {
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
    DisposableEffect(origin, submittedQuery) {
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

    Column(Modifier.fillMaxSize()) {
        // Centred under a width ceiling: past a point the extra width of a wide
        // monitor stops being room and starts stretching the search field and the
        // cards, which keep their height while their width tracks the window. The
        // cap sits above a 1920 window's centre, so nothing moves at or below FHD.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
        Column(
            // Height only. fillMaxSize would pin the minimum width to the full
            // width as well, and a ceiling cannot take the maximum below the
            // minimum -- the cap was there and did nothing.
            Modifier.fillMaxHeight()
                .widthIn(max = Dimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Source switcher -- one chip per registered catalogue origin.
            if (origins.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    origins.forEach { o ->
                        SourceTab(label = originLabel(o), selected = o == origin) { origin = o }
                        PuppetClick("browse.source.${o.name}") { origin = o }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SearchField(
                value         = query,
                onValueChange = { query = it },
                placeholder   = s.browseSearchPlaceholder,
            )
            Spacer(Modifier.height(16.dp))

            when (val st = state) {
                BrowseState.Loading -> BrowseLoading()
                BrowseState.Empty   -> BrowseEmpty(onRetry = { retryTick++ })
                is BrowseState.Error -> BrowseError(message = st.message, onRetry = { retryTick++ })
                is BrowseState.Loaded -> BrowseList(packs = st.packs, listState = listState, onOpenPack = onOpenPack)
            }
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

/** Compact, rounded, filled search field (a bare OutlinedTextField sat too tall and read as a form input). */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value         = value,
        onValueChange = onValueChange,
        singleLine    = true,
        textStyle     = MaterialTheme.typography.bodyMedium.copy(color = NxTheme.colors.textPrimary),
        cursorBrush   = SolidColor(NxTheme.colors.primary),
        modifier      = Modifier.fillMaxWidth(),
    ) { inner ->
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(NxTheme.colors.surface)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                NxIcon.Search,
                contentDescription = null,
                tint               = NxTheme.colors.textSecondary,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text  = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NxTheme.colors.textSecondary,
                    )
                }
                inner()
            }
        }
    }
}

@Composable
private fun SourceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) NxTheme.colors.primary else NxTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (selected) Color.White else NxTheme.colors.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun originLabel(origin: PackOrigin): String = when (origin) {
    PackOrigin.Mirror -> "Hivens"
    PackOrigin.Modrinth -> "Modrinth"
    PackOrigin.Smartycraft -> "SmartyCraft"
    PackOrigin.Local -> "Local"
    PackOrigin.Unknown -> "Other"
}

@Composable
private fun BrowseLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = NxTheme.colors.primary.copy(alpha = 0.55f),
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
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = s.browseEmptyMessage,
                style     = MaterialTheme.typography.bodyMedium,
                color     = NxTheme.colors.textSecondary,
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
            BrowsePackCard(pack = pack, onClick = { onOpenPack(pack) })
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
