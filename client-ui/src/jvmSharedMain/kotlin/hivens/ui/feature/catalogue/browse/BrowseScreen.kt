package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.ResultsScroll
import hivens.catalogue.api.ScrollMark
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.i18n.api.Strings
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxBasicTextField
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Dimens
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import java.util.UUID
/**
 * Browse = the catalogue of everything installable: packs from every source the modules
 * brought in, and mods, resource packs and shaders from the project sources.
 *
 * The screen is a widget surface and owns none of its controls. What is searched,
 * in what order and into which pack is chosen in the right rail's browse family;
 * the search box and the results are this surface's two slots. All of them meet at
 * the window's [CatalogueKeys.Browse], so a widget moved, removed or added in the
 * editor needs nothing from its neighbours.
 *
 * What the screen does own is switching the rail to its family for as long as Browse
 * is up, naming the pack installs go into each time it comes up, so the catalogue
 * module reads it off disk anew for every widget to ask the same answer, and showing
 * the window's results for as long as it is up, which is what runs the search.
 */
@Composable
fun BrowseScreen(
    /**
     * The pack this browse was opened from. It becomes the install target, and the
     * search moves to a kind the pack takes each time the reader asks for this browse
     * (see [CatalogueKeys.Aim]): a reader who then picks something else keeps it.
     */
    intoInstanceId: String? = null,
) {
    PuppetScreen(if (intoInstanceId == null) "Browse" else "BrowseInto")

    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull()
    val setTarget = rememberScopedCommand(CatalogueKeys.SetTarget)
    val showResults = rememberScopedCommand(CatalogueKeys.ShowResults)
    val hideResults = rememberScopedCommand(CatalogueKeys.HideResults)
    val families = LocalSurfaceFamilies.current

    // This visit, as the family's owner, so a page leaving over it hands the rail
    // back to Browse rather than to whatever it showed itself.
    val railOwner = remember { Any() }
    DisposableEffect(railOwner) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.BROWSE, railOwner)
        onDispose { families.reset(RIGHT_RAIL_SURFACE, railOwner) }
    }

    // The pack is the screen's own subject, so it is the target whenever the screen
    // comes up, and the one the rail chose is read again: a pack updated since to
    // another game version kept the earlier reading otherwise. The kind moves only when
    // the reader asked for this browse, which the catalogue module keeps apart.
    LaunchedEffect(intoInstanceId, setTarget != null) {
        val set = setTarget ?: return@LaunchedEffect
        set(intoInstanceId ?: browse?.targetId)
    }
    // The search runs while this screen shows its results and stops when it leaves,
    // so a source that polls is not asked while nobody looks. Coming back runs it again
    // behind what it kept, so an install from a project page or a removal in the
    // Content tab shows on the card.
    val viewer = remember { UUID.randomUUID().toString() }
    DisposableEffect(viewer, showResults, hideResults) {
        showResults?.invoke(viewer)
        onDispose { hideResults?.invoke(viewer) }
    }

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

internal const val BROWSE_SURFACE = "browse"

/**
 * Packs from one source: the window's search as the catalogue module answers it, with
 * what was shown last up first, the source's fresh answer behind it, and the pages
 * after the first asked for as the reader nears the end.
 */
@Composable
internal fun PackBrowse(
    results: SearchResults,
    onOpenPack: (CatalogueCard) -> Unit,
    /**
     * Which of the listed packs are drawn, for a choice the source does not answer
     * itself. The list underneath is kept whole, so taking the choice back shows
     * what it hid without asking the source again.
     */
    shown: (CatalogueCard) -> Boolean = { true },
    /** What the source packs are listed from is called, for what the list says when it has nothing to show. */
    sourceName: String = "",
) {
    val s = nexiraStrings()
    val imageContext = LocalPlatformContext.current
    val retry = rememberScopedAction(CatalogueKeys.RetryResults)
    val more = rememberScopedAction(CatalogueKeys.MoreResults)
    val scroll = rememberScopedCommand(CatalogueKeys.ScrollResults)

    PuppetClick("browse.retry") { retry?.invoke() }

    // Both images of every card on the page, resolved as the page lands rather than as
    // a card scrolls into view, so a card does not change under the eye a moment after
    // it is read. Only what the page just added: re-enqueueing the whole list on every
    // growth asks the loader for page one's images again on page five.
    var prefetched by remember(results.question) { mutableIntStateOf(0) }
    LaunchedEffect(results.question, results.packs) {
        val packs = results.packs
        if (results.status != SearchStatus.Loaded || packs.size <= prefetched) return@LaunchedEffect
        prefetchCardArt(imageContext, packs.subList(prefetched, packs.size))
        prefetched = packs.size
    }

    when (val status = results.status) {
        SearchStatus.Loading -> BrowseLoading()
        SearchStatus.Empty, SearchStatus.NoSource -> BrowseEmpty(
            notice = checkNotNull(browseNotice(status, sourceName, s)),
            // Nobody was asked where no source is loaded, so there is nothing to ask again.
            onRetry = if (status == SearchStatus.Empty) fun() { retry?.invoke() } else null,
        )
        is SearchStatus.Failed -> BrowseError(notice = checkNotNull(browseNotice(status, sourceName, s)), onRetry = { retry?.invoke() })
        SearchStatus.Loaded -> {
            val visible = results.packs.filter(shown)
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.text("contentEmpty"), style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                }
            } else {
                // One list per question: it starts where the reader left that question's
                // answer, and says where they leave it under that question's name.
                key(results.question) {
                    val question = results.question
                    val listState = rememberLazyListState(results.scroll.index, results.scroll.offset)
                    // A fresh first page that replaced the list sends the reader to its top.
                    val firstTop = remember { results.toTop }
                    LaunchedEffect(results.toTop) { if (results.toTop != firstTop) listState.requestScrollToItem(0) }
                    DisposableEffect(listState) {
                        onDispose {
                            question?.let { scroll?.invoke(ResultsScroll(it, ScrollMark(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))) }
                        }
                    }
                    // Only the first page was ever asked for once, so a catalogue with more
                    // to give stopped at twenty results with nothing saying there were more.
                    LaunchedEffect(listState, results.packs.size, results.endReached) {
                        if (results.endReached) return@LaunchedEffect
                        val total = results.packs.size
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                            .collect { last -> if (last >= total - 3) more?.invoke() }
                    }
                    BrowseList(packs = visible, listState = listState, onOpenPack = onOpenPack)
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
private fun prefetchCardArt(context: PlatformContext, packs: List<CatalogueCard>) {
    val loader = SingletonImageLoader.get(context)
    packs.forEach { pack ->
        listOfNotNull(pack.iconUrl, pack.bannerUrl).forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/**
 * The words of a field that a command keeps, as the field shows them, and what to call
 * as the person types.
 *
 * The field shows what was typed at once: the words come back through the window's
 * browse a moment later, and a field that waited for them dropped keys typed in the
 * meantime. A change that did not come from here, the words a tag search cleared, is
 * taken over as it arrives.
 */
@Composable
internal fun rememberEchoedText(kept: String, send: (String) -> Unit): Pair<String, (String) -> Unit> {
    val text = remember { mutableStateOf(kept) }
    // Sent and not yet come back, oldest first. Read only by the effect below and the setter.
    val sent = remember { ArrayDeque<String>() }
    LaunchedEffect(kept) {
        val at = sent.indexOf(kept)
        if (at >= 0) {
            repeat(at + 1) { sent.removeFirst() }
        } else if (kept != text.value) {
            sent.clear()
            text.value = kept
        }
    }
    val type: (String) -> Unit = remember(send) {
        { value ->
            text.value = value
            sent.addLast(value)
            send(value)
        }
    }
    return text.value to type
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
    val glass by animateColorAsState(if (focused) NxColor.lead() else NxInk.quiet, animationSpec = Motion.colorShift.of(), label = "searchGlass")
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
            NxBasicTextField(
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

/** What Browse says over a list with nothing to show: a title and the words under it. */
internal data class BrowseNotice(val title: String, val message: String)

/**
 * What a pack list with nothing to show says for [status], in the name of the source
 * called [source], or null for a list that shows something or is still on its way.
 * No source loaded is its own case: nothing was asked, so nothing is said to be empty
 * or unreachable.
 */
internal fun browseNotice(status: SearchStatus, source: String, s: Strings): BrowseNotice? = when (status) {
    SearchStatus.Empty -> BrowseNotice(s.text("browseEmptyTitle"), s.text("browseEmptyMessage", source))
    is SearchStatus.Failed -> BrowseNotice(s.text("browseErrorTitle", source), status.message ?: s.text("browseErrorMessage", source))
    SearchStatus.NoSource -> BrowseNotice(s.text("browseNoSourceTitle"), s.text("browseNoSourceMessage"))
    SearchStatus.Loading, SearchStatus.Loaded -> null
}

/** [notice] centred, with a retry under it where [onRetry] is one. */
@Composable
internal fun BrowseEmpty(notice: BrowseNotice, onRetry: (() -> Unit)?) {
    val s = nexiraStrings()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text       = notice.title,
                style      = MaterialTheme.typography.titleLarge,
                color      = NxInk.main,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = notice.message,
                style     = MaterialTheme.typography.bodyMedium,
                color     = NxInk.quiet,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 420.dp),
            )
            onRetry?.let { NxButton(label = s.text("browseRetry"), onClick = it) }
        }
    }
}

@Composable
private fun BrowseError(notice: BrowseNotice, onRetry: () -> Unit) {
    val s = nexiraStrings()
    RetryStateBlock(
        title      = notice.title,
        message    = notice.message,
        retryLabel = s.text("browseRetry"),
        onRetry    = onRetry,
        modifier   = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BrowseList(
    packs: List<CatalogueCard>,
    listState: LazyListState,
    onOpenPack: (CatalogueCard) -> Unit,
) {
    LazyColumn(
        state               = listState,
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = packs, key = { it.key }) { pack ->
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
