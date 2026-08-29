package hivens.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.INewsFeed
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.platform.SystemActions
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.shell.NewsImageSource
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun CompactNewsFeed(
    sslBypass: Boolean = false,
    maxItems: Int = 0,
    showTitle: Boolean = true,
    imageSource: NewsImageSource = NewsImageSource.Thumbnail,
    modifier: Modifier = Modifier,
) {
    val feed: INewsFeed = koinInject()
    val protocolConfig: ServerProtocolConfig = koinInject()
    val s       = LocalStrings.current

    var news    by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var query   by remember { mutableStateOf("") }
    // The query the list is actually filtered by, settled after the typing
    // stops. Filtering hundreds of loaded rows on every keystroke is work the
    // reader pays for mid-word (the mod browser's search settles the same way).
    var settled by remember { mutableStateOf("") }
    // The page to ask for next, and whether the archive has one. The feed is
    // walked a page at a time: the rail opens on one page and the rest arrive as
    // the reader reaches the end of what is loaded.
    var nextPage by remember { mutableStateOf(2) }
    var exhausted by remember { mutableStateOf(false) }
    // The feed fell back to the dashboard's three. It looks complete -- one page,
    // nothing after it -- so without this the rail would settle for the floor
    // and offer no way back to the archive.
    var onFloor by remember { mutableStateOf(false) }
    // Bumped by the retry button to re-trigger the fetch effect. The effect
    // re-runs on (a) initial composition, (b) an SSL bypass grant for the
    // host, (c) an explicit retry click, so a first-call failure doesn't
    // strand the strip at "no news" forever.
    var retryTick by remember { mutableStateOf(0) }

    // Both loaders latch a flag before suspending, so both release it in a
    // finally: a cancelled load that left `loadingMore` set would end paging for
    // good and leave the tail spinning on work nobody is doing.
    suspend fun loadFirst(forceRefresh: Boolean) {
        loading = true
        try {
            val page = feed.page(1, forceRefresh)
            news = page.items.distinctBy { it.id }
            nextPage = 2
            exhausted = !page.hasMore
            onFloor = page.fallback
        } finally {
            loading = false
        }
    }

    suspend fun loadNext() {
        if (loadingMore || exhausted) return
        loadingMore = true
        try {
            val page = feed.page(nextPage)
            // Keyed by id rather than appended blind: an entry published while
            // the reader is walking the archive shifts every later page down by
            // one, and the row it pushes over would otherwise arrive twice.
            val known = news.mapTo(HashSet()) { it.id }
            val fresh = page.items.filter { known.add(it.id) }
            news = news + fresh
            // A page that added no row ends the walk -- the end of the archive, a
            // page that failed, or one that repeated what is already loaded. It
            // has to be judged on what arrived rather than on the page being
            // non-empty: a page of rows already held would otherwise leave the
            // walk running with nothing to show for each pass.
            exhausted = !page.hasMore || fresh.isEmpty()
            nextPage += 1
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(retryTick, sslBypass) {
        // Initial composition + bypass changes: use the cache-aware path,
        // since the first call has nothing cached and bypass-driven retries
        // only make sense when news is empty anyway (working strips
        // shouldn't flicker on Settings flips). Explicit retry skips the
        // cache so a user clicking Retry after network recovery actually
        // hits upstream, even if a successful fetch had already cached an
        // empty news list earlier in the session. A feed sitting on the
        // dashboard floor counts as empty here: granting the bypass is exactly
        // the act that can put the archive back within reach.
        val explicit = retryTick > 0
        if (explicit) loadFirst(forceRefresh = true)
        else if (news.isEmpty() || onFloor) loadFirst(forceRefresh = false)
    }

    LaunchedEffect(query) {
        delay(FILTER_DEBOUNCE_MS.milliseconds)
        settled = query
    }

    val listState = rememberLazyListState()
    // The count is read through a snapshot rather than keyed on: it is edited
    // with a slider, so an effect keyed on it restarts on every frame of a drag
    // -- and a restart mid-fetch cancels the page it is waiting for.
    val cap by rememberUpdatedState(maxItems)

    // A widget that was told to show twenty fills up to twenty without being
    // scrolled: the count is what it promises, not what it stops at.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(cap, loading, news.size) }.collect { (target, busy, loaded) ->
            if (target > 0 && !busy && loaded < target) loadNext()
        }
    }

    // An uncapped rail pages as it is read instead. The tail row is excluded
    // from the index it watches -- it appears and disappears with the load it
    // reports, and counting it would make the load its own trigger. A first
    // emission arrives before the list has ever been measured, which is why an
    // unlaid-out list (-1) is not a reader at the end of one.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull { it.key != FEED_TAIL_KEY }?.index ?: -1
        }.collect { last ->
            // Paging follows the loaded feed, so it pauses while a filter is
            // narrowing it: the reader reaches the end of four matches in a
            // second, and chasing that would walk the whole archive to satisfy
            // a search of what is already here.
            if (cap == 0 && settled.isBlank() && last >= 0 && last >= news.size - PREFETCH_ROWS) {
                loadNext()
            }
        }
    }

    val shown = remember(news, settled, maxItems) {
        news.asSequence()
            .filter { settled.isBlank() || it.title.contains(settled, ignoreCase = true) }
            .let { seq -> if (maxItems > 0) seq.take(maxItems) else seq }
            .toList()
    }

    // One formatter for the whole list: built per row it was a pattern parse per
    // row, and the pattern is the same for every one of them.
    val dateFormat = remember(s.locale) {
        DateTimeFormatter.ofPattern("d MMM yyyy", s.locale).withZone(ZoneId.systemDefault())
    }

    Column(modifier = modifier) {
        // Section header (optional -- a tight rail may prefer to drop it).
        if (showTitle) {
            Text(
                text       = s.newsTitle,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color      = NxTheme.colors.textSecondary,
                modifier   = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            HorizontalDivider(color = NxTheme.colors.outline)
        }

        // Filter field -- only once a loaded, non-empty feed gives something to
        // filter; the search narrows by title.
        if (!loading && news.isNotEmpty()) {
            NewsFilterField(query = query, onQueryChange = { query = it })
            HorizontalDivider(color = NxTheme.colors.outline)
        }

        // Weighted so the list owns the remaining height and scrolls within it,
        // instead of overflowing the rail and fighting its neighbours.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> NewsSkeleton()

                news.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text  = s.newsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                        // Explicit retry covers the "network came back but no
                        // setting was touched" path -- the LaunchedEffect above
                        // only re-runs on bypass changes.
                        TextButton(onClick = { retryTick++ }) {
                            Text(s.updateRetry, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                shown.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = s.newsEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }

                else -> {
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    Box(Modifier.fillMaxSize().hoverable(hover)) {
                        LazyColumn(
                            state          = listState,
                            modifier       = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(shown, key = { it.id }, contentType = { "news" }) { item ->
                                CompactNewsItem(
                                    item        = item,
                                    baseUrl     = protocolConfig.baseUrl,
                                    imageSource = imageSource,
                                    dateFormat  = dateFormat,
                                )
                                HorizontalDivider(
                                    color    = NxTheme.colors.outline,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            // Always emitted, so what it says changes without the
                            // list gaining and losing a row -- and so the state it
                            // reads recomposes this row rather than the whole feed.
                            item(key = FEED_TAIL_KEY, contentType = "tail") {
                                when {
                                    loadingMore -> FeedTail()
                                    // The archive was out of reach and these three
                                    // came from the dashboard. Say so where the feed
                                    // ends, which is where the reader finds out it
                                    // was shorter than expected.
                                    onFloor -> FeedRetry(onRetry = { retryTick++ })
                                    else -> Unit
                                }
                            }
                        }
                        NxVerticalScrollbar(
                            adapter  = rememberScrollbarAdapter(listState),
                            revealed = hovered || listState.isScrollInProgress,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** How close to the end of the loaded rows the next page is asked for. */
private const val PREFETCH_ROWS = 3

/** How long the filter waits for the typing to stop before it narrows the list. */
private const val FILTER_DEBOUNCE_MS = 350L

/** Stable key for the tail row, so it never collides with a news id. */
private const val FEED_TAIL_KEY = "feed-tail"

/** The "there is more coming" row under the last loaded entry. */
@Composable
private fun FeedTail() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color       = NxTheme.colors.primary.copy(alpha = 0.6f),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(16.dp),
        )
    }
}

/** The way back to the archive when the feed is sitting on the dashboard's three. */
@Composable
private fun FeedRetry(onRetry: () -> Unit) {
    val s = LocalStrings.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRetry) {
            Text(s.updateRetry, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Compact glass search field. Narrows the feed by title so a long news list
// stays scannable in the rail.
@Composable
private fun NewsFilterField(query: String, onQueryChange: (String) -> Unit) {
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(NxTheme.colors.surfaceContainerHigh)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Symbol(icon = NxIcon.Search,
            contentDescription = null,
            tint               = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value         = query,
                onValueChange = onQueryChange,
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    text  = s.newsFilterPlaceholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
                )
            }
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Symbol(icon = NxIcon.Close,
                    contentDescription = s.newsFilterClear,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ─── Skeleton loader ──────────────────────────────────────────────────────────

@Composable
private fun NewsSkeleton() {
    // Two distinct tonal roles rather than one surface at two alphas: the helper
    // that resolved a tint returned the same opaque colour for every alpha on a
    // light palette, so all three stops were identical and the skeleton did not
    // shimmer at all.
    val colors = NxTheme.colors
    val shimmerColors = listOf(
        colors.surfaceContainer,
        colors.surfaceContainerHigh,
        colors.surfaceContainer,
    )

    // A still style parks the sweep off-frame rather than restarting it every
    // frame, which is what a collapsed duration would do to an endless loop.
    val sweep = Motion.sweep
    val translateAnim by if (Motion.isStill) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue  = 0f,
            targetValue   = 1000f,
            animationSpec = infiniteRepeatable(sweep.of(), RepeatMode.Restart),
            label = "shimmer"
        )
    }

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(translateAnim - 300f, 0f),
        end    = Offset(translateAnim, 0f)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(4) {
            SkeletonNewsItem(brush)
            HorizontalDivider(
                color    = NxTheme.colors.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun SkeletonNewsItem(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )

        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title line
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            // Second title line (shorter)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            // Date line
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}

// ─── News item ────────────────────────────────────────────────────────────────

@Composable
private fun CompactNewsItem(
    item: NewsItem,
    baseUrl: String,
    imageSource: NewsImageSource,
    dateFormat: DateTimeFormatter,
) {
    // The entry's page is addressed by its id, so every row opens -- which the
    // arrow hint says. It used to be gated on the row having an image, from when
    // that was the only field standing in for a link.
    val date = remember(item.dateEpochSeconds, dateFormat) {
        if (item.dateEpochSeconds <= 0L) null
        else runCatching { dateFormat.format(Instant.ofEpochSecond(item.dateEpochSeconds)) }.getOrNull()
    }
    // What this row asks for, and what it falls back to if that is missing
    // upstream: the two sizes are published side by side and either can be the
    // one that is not there.
    val wanted = when (imageSource) {
        NewsImageSource.Thumbnail -> item.thumbnailUrl ?: item.imageUrl
        NewsImageSource.Full      -> item.imageUrl ?: item.thumbnailUrl
    }
    val alternate = when (imageSource) {
        NewsImageSource.Thumbnail -> item.imageUrl
        NewsImageSource.Full      -> item.thumbnailUrl
    }?.takeIf { it != wanted }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { SystemActions.openUrl("$baseUrl/news${item.id}") }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(NxTheme.colors.surface)
        ) {
            // The fallback rides on the failure rather than on a subcomposition
            // per row: a row is cheap and there are hundreds of them. Keyed on the
            // url so a recycled row starts over rather than inheriting a miss.
            var missing by remember(wanted) { mutableStateOf(false) }
            val model = if (missing) alternate else wanted
            if (model != null) {
                AsyncImage(
                    model              = model,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                    onError            = { if (!missing && alternate != null) missing = true },
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = item.title,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color      = NxTheme.colors.textPrimary,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            if (date != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.primary.copy(alpha = 0.7f)
                )
            }
        }

        // Subtle arrow hint that item is clickable
        Text(
            text  = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = NxTheme.colors.textSecondary.copy(alpha = 0.4f)
        )
    }
}

// ─── Auth loading slot ────────────────────────────────────────────────────────
