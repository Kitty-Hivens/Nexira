package hivens.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import hivens.core.api.interfaces.INewsFeed
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.platform.SystemActions
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CompactNewsFeed(
    sslBypass: Boolean = false,
    maxItems: Int = 0,
    showTitle: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val feed: INewsFeed = koinInject()
    val s       = LocalStrings.current

    var news    by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var query   by remember { mutableStateOf("") }
    // The page to ask for next, and whether the archive has one. The feed is
    // walked a page at a time: the rail opens on one page and the rest arrive as
    // the reader reaches the end of what is loaded.
    var nextPage by remember { mutableStateOf(2) }
    var exhausted by remember { mutableStateOf(false) }
    // Bumped by the retry button to re-trigger the fetch effect. The effect
    // re-runs on (a) initial composition, (b) an SSL bypass grant for the
    // host, (c) an explicit retry click, so a first-call failure doesn't
    // strand the strip at "no news" forever.
    var retryTick by remember { mutableStateOf(0) }

    suspend fun loadFirst(forceRefresh: Boolean) {
        loading = true
        val page = feed.page(1, forceRefresh)
        news = page.items
        nextPage = 2
        exhausted = !page.hasMore
        loading = false
    }

    suspend fun loadNext() {
        if (loadingMore || exhausted) return
        loadingMore = true
        val page = feed.page(nextPage)
        // Keyed by id rather than appended blind: an entry published while the
        // reader is walking the archive shifts every later page down by one, and
        // the row it pushes over would otherwise arrive twice.
        val known = news.mapTo(HashSet()) { it.id }
        news = news + page.items.filterNot { it.id in known }
        // A page that came back with nothing ends the walk, whether it was the
        // end of the archive or a page that failed to load: retrying it on every
        // scroll event is how a rail with no network turns into a request loop.
        // Reopening the rail starts the walk over.
        exhausted = !page.hasMore || page.items.isEmpty()
        nextPage += 1
        loadingMore = false
    }

    LaunchedEffect(retryTick, sslBypass) {
        // Initial composition + bypass changes: use the cache-aware path,
        // since the first call has nothing cached and bypass-driven retries
        // only make sense when news is empty anyway (working strips
        // shouldn't flicker on Settings flips). Explicit retry skips the
        // cache so a user clicking Retry after network recovery actually
        // hits upstream, even if a successful fetch had already cached an
        // empty news list earlier in the session.
        val explicit = retryTick > 0
        if (explicit) loadFirst(forceRefresh = true)
        else if (news.isEmpty()) loadFirst(forceRefresh = false)
    }

    val listState = rememberLazyListState()

    // A widget that was told to show twenty fills up to twenty without being
    // scrolled -- the count is what it promises, not what it stops at. An
    // uncapped rail pages on scroll instead (below).
    //
    // Both effects are keyed on the setting, never on the state they watch: a
    // key that moves restarts the effect, and restarting it mid-fetch cancels
    // the page it is waiting for -- leaving the feed convinced a load it no
    // longer has is still running. The signals come through a snapshot instead.
    LaunchedEffect(maxItems) {
        if (maxItems <= 0) return@LaunchedEffect
        snapshotFlow { loading to news.size }.collect { (busy, loaded) ->
            if (!busy && loaded < maxItems) loadNext()
        }
    }

    LaunchedEffect(maxItems, listState) {
        if (maxItems > 0) return@LaunchedEffect
        // The last row in view, against everything loaded: asking a few rows
        // early is what keeps the feed going rather than stopping to load.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= news.size - PREFETCH_ROWS) loadNext() }
    }

    val shown = remember(news, query, maxItems) {
        news.asSequence()
            // The list is keyed by news id; upstream repeating one across a page
            // boundary would otherwise be a crash rather than a duplicate row.
            .distinctBy { it.id }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .let { seq -> if (maxItems > 0) seq.take(maxItems) else seq }
            .toList()
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
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            CompactNewsItem(item = item)
                            HorizontalDivider(
                                color    = NxTheme.colors.outline,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        if (loadingMore) {
                            item(key = FEED_TAIL_KEY) { FeedTail() }
                        }
                    }
                }
            }
        }
    }
}

/** How close to the end of the loaded rows the next page is asked for. */
private const val PREFETCH_ROWS = 3

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
    // Two distinct tonal roles rather than one surface at two alphas: on a light
    // palette glassSurfaceAlpha returns the same opaque colour for every alpha, so
    // all three stops were identical and the skeleton did not shimmer at all.
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
private fun CompactNewsItem(item: NewsItem) {
    val protocolConfig: ServerProtocolConfig = koinInject()
    // Try to open a URL if the NewsItem has one; the click hook is ready for
    // when the backend sends real ones.
    val canOpenUrl = item.imageUrl != null  // reuse as proxy; swap for item.url when available
    val locale = LocalStrings.current.locale
    // Upstream dates were formatted in the fetch layer, in Russian, whatever the
    // launcher was set to. They arrive as an epoch second now and are read here.
    val date = remember(item.dateEpochSeconds, locale) {
        if (item.dateEpochSeconds <= 0L) null
        else runCatching {
            DateTimeFormatter.ofPattern("d MMM yyyy", locale)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(item.dateEpochSeconds))
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenUrl) {
                // Build a best-effort URL from the image URL pattern:
                // https://smartycraft.ru/images/news/mini/news1.jpg  ->  https://smartycraft.ru/news{id}
                SystemActions.openUrl("${protocolConfig.baseUrl}/news${item.id}")
            }
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
            if (item.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(item.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
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
        if (canOpenUrl) {
            Text(
                text  = "›",
                style = MaterialTheme.typography.bodyMedium,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.4f)
            )
        }
    }
}

// ─── Auth loading slot ────────────────────────────────────────────────────────

