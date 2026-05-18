package hivens.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import hivens.core.api.interfaces.IServerListService
import hivens.core.data.NewsItem
import hivens.launcher.network.NetworkState
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SystemActions
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CompactNewsFeed")

@Composable
fun CompactNewsFeed(
    sslBypass: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val serverListService: IServerListService = koinInject()
    val s       = LocalStrings.current

    var news    by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Bumped by the retry button to re-trigger the fetch effect. Pre-fix the
    // feed had a single LaunchedEffect(Unit) -- a first-call failure stuck
    // the strip at "no news" forever even after the user fixed their proxy
    // toggle / network. Now: refetch fires on (a) initial composition,
    // (b) force-proxy toggle change, (c) SSL bypass grant for the host,
    // (d) explicit retry click. Each is keyed via this counter.
    var retryTick by remember { mutableStateOf(0) }

    val forceProxy by NetworkState.forceProxyState.collectAsState()

    suspend fun fetch() {
        loading = true
        try {
            val data = withContext(Dispatchers.IO) { serverListService.fetchDashboardData().get() }
            news = data.news
        } catch (e: Exception) {
            log.warn("News fetch failed", e)
        }
        loading = false
    }

    LaunchedEffect(retryTick, forceProxy, sslBypass) {
        // Refetch on initial composition + when the user explicitly retries.
        // For toggle / bypass changes we only refetch if news is empty --
        // a working strip shouldn't flicker just because Settings flipped.
        val explicit = retryTick > 0
        if (explicit || news.isEmpty()) fetch()
    }

    Column(modifier = modifier) {
        // Section header
        Text(
            text       = s.newsTitle,
            style      = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color      = CelestiaTheme.colors.textSecondary,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        HorizontalDivider(color = CelestiaTheme.colors.surface.copy(alpha = 0.6f))

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
                        color = CelestiaTheme.colors.textSecondary,
                    )
                    // Explicit retry covers the "network came back but no
                    // toggle was touched" path -- the LaunchedEffect above
                    // only re-runs on toggle / bypass changes.
                    TextButton(onClick = { retryTick++ }) {
                        Text(s.updateRetry, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(news) { item ->
                    CompactNewsItem(item = item)
                    HorizontalDivider(
                        color    = CelestiaTheme.colors.surface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// ─── Skeleton loader ──────────────────────────────────────────────────────────

@Composable
private fun NewsSkeleton() {
    val shimmerColors = listOf(
        CelestiaTheme.colors.surface.copy(alpha = 0.6f),
        CelestiaTheme.colors.surface.copy(alpha = 0.25f),
        CelestiaTheme.colors.surface.copy(alpha = 0.6f),
    )

    val transition = rememberInfiniteTransition(label = "skeleton")
    val translateAnim by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(translateAnim - 300f, 0f),
        end    = Offset(translateAnim, 0f)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(4) {
            SkeletonNewsItem(brush)
            HorizontalDivider(
                color    = CelestiaTheme.colors.surface.copy(alpha = 0.4f),
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
    // Try to open a URL if the NewsItem has one (currently description holds "Views: N",
    // but we keep the click hook ready for when the backend sends real URLs)
    val canOpenUrl = item.imageUrl != null  // reuse as proxy; swap for item.url when available

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
                .background(CelestiaTheme.colors.surface)
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
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = item.date,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.primary.copy(alpha = 0.7f)
            )
        }

        // Subtle arrow hint that item is clickable
        if (canOpenUrl) {
            Text(
                text  = "›",
                style = MaterialTheme.typography.bodyMedium,
                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f)
            )
        }
    }
}

// ─── Auth loading slot ────────────────────────────────────────────────────────

