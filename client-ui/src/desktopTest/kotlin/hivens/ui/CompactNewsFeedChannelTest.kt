package hivens.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.core.api.interfaces.INewsFeed
import hivens.core.data.NewsChannelPolicy
import hivens.core.data.NewsItem
import hivens.core.data.NewsPage
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.theme.NxTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rail swaps its channel in place. What was loaded belongs to the channel it
 * came from, so the new one is asked for its own first page rather than shown
 * under the old one's rows.
 */
class CompactNewsFeedChannelTest {

    @AfterTest fun tearDown() = stopKoin()

    private class CountingFeed(private val title: String) : INewsFeed {
        val firstPages = AtomicInteger()
        override val policy = NewsChannelPolicy()
        override suspend fun page(page: Int, forceRefresh: Boolean): NewsPage {
            if (page == 1) firstPages.incrementAndGet()
            return NewsPage(items = listOf(NewsItem(id = 1, title = title)), page = page, totalPages = 1)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a new channel loads its own first page`() {
        startKoin { modules(module { single { ServerProtocolConfig() } }) }
        val upstream = CountingFeed("upstream")
        val curated = CountingFeed("curated")
        val feed = mutableStateOf<INewsFeed>(upstream)
        // Built and drawn on the event thread: the rail subscribes a snapshotFlow.
        val scene = onEventThread {
            ImageComposeScene(320, 300, density = Density(1f)) {
                NxTheme(dark = true) { CompactNewsFeed(maxItems = 4, feed = feed.value) }
            }
        }
        var t = 0L
        fun pump() = repeat(20) {
            onEventThread { scene.render(t).close() }
            t += FRAME_NANOS
            Thread.sleep(5)
        }

        pump()
        assertTrue(upstream.firstPages.get() > 0, "the first channel never loaded")
        onEventThread { feed.value = curated }
        pump()
        onEventThread { scene.close() }

        assertTrue(curated.firstPages.get() > 0, "the old channel's rows stayed up under the new one")
    }
}
