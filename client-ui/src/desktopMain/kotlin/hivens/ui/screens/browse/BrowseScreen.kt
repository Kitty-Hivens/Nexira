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

    var origin by remember { mutableStateOf(origins.firstOrNull() ?: PackOrigin.Mirror) }
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
    var page by remember { mutableIntStateOf(0) }
    var endReached by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // What this source and query were last showing goes up first, before anything
    // is asked of the catalogue. A spinner belongs over an empty screen, not over
    // a list the user was reading a moment ago and is about to get back nearly
    // unchanged -- which is every flip of the source switcher and every trip out
    // of the screen and back.
    LaunchedEffect(origin, submittedQuery, retryTick) {
        val remembered = session.get(origin, submittedQuery)
        if (remembered != null) {
            state = BrowseState.Loaded(remembered.packs)
            page = remembered.nextPage
            endReached = remembered.endReached
        } else {
            state = BrowseState.Loading
            page = 0
            endReached = false
        }
        val catalogue = registry.forOrigin(origin)
            ?: return@LaunchedEffect run { state = BrowseState.Empty }
        try {
            // Stale first, fresh behind it. Assigning an equal list is not a
            // repaint -- the state is compared, not trusted -- so a refresh that
            // found nothing new costs the screen nothing.
            catalogue.searchStream(submittedQuery, page = 0)
                .flowOn(Dispatchers.IO)
                .collect { packs ->
                    if (packs.isEmpty()) {
                        if (state !is BrowseState.Loaded) state = BrowseState.Empty
                        return@collect
                    }
                    // The fresh page is compared against the front of what is
                    // shown, not swapped in over it. Unchanged is the ordinary
                    // answer, and there the pages scrolled on top still follow
                    // from it and must survive -- replacing the list wholesale
                    // would take a player back to the first twenty results a
                    // second after they scrolled past them.
                    val shown = (state as? BrowseState.Loaded)?.packs
                    if (shown != null && shown.size >= packs.size && shown.subList(0, packs.size) == packs) {
                        return@collect
                    }
                    page = 0
                    endReached = false
                    state = BrowseState.Loaded(packs)
                    session.put(origin, submittedQuery, BrowseSession.Snapshot(packs, nextPage = 0, endReached = false))
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
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
    LaunchedEffect(state) {
        val packs = (state as? BrowseState.Loaded)?.packs ?: return@LaunchedEffect
        prefetchCardArt(imageContext, packs)
    }

    // Only the first page was ever asked for, so a catalogue with more to give
    // simply stopped at twenty results with nothing saying there were more.
    LaunchedEffect(listState, state, endReached) {
        if (state !is BrowseState.Loaded || endReached) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val current = (state as? BrowseState.Loaded)?.packs ?: return@collect
                if (loadingMore || endReached || last < current.size - 3) return@collect
                loadingMore = true
                runCatching {
                    val catalogue = registry.forOrigin(origin)
                    val next = if (catalogue == null) emptyList()
                    else withContext(Dispatchers.IO) { catalogue.search(submittedQuery, page = page + 1) }
                    val seen = current.mapTo(HashSet()) { "${'$'}{it.origin}:${'$'}{it.id}" }
                    val fresh = next.filterNot { "${'$'}{it.origin}:${'$'}{it.id}" in seen }
                    if (fresh.isEmpty()) endReached = true else {
                        page += 1
                        val grown = current + fresh
                        state = BrowseState.Loaded(grown)
                        session.put(origin, submittedQuery, BrowseSession.Snapshot(grown, nextPage = page, endReached = false))
                    }
                }.onFailure { endReached = true }
                loadingMore = false
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
        items(items = packs, key = { "${it.origin}:${it.id}" }) { pack ->
            BrowsePackCard(pack = pack, onClick = { onOpenPack(pack) })
            PuppetClick("browse.open.${pack.origin}.${pack.id}") { onOpenPack(pack) }
        }
    }
}

sealed class BrowseState {
    object Loading : BrowseState()
    object Empty   : BrowseState()
    data class Loaded(val packs: List<CataloguePack>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
