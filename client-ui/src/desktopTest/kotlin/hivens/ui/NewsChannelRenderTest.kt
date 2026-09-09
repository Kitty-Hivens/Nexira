package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.INewsFeed
import hivens.core.data.NewsChannelPolicy
import hivens.core.data.NewsItem
import hivens.core.data.NewsOrder
import hivens.core.data.NewsPage
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.news.CuratedNewsFeed
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The two news channels side by side, because the difference between them is
 * entirely visual policy: one channel's rows open at their source and the other's
 * are text.
 *
 * A rail that still drew the click affordance on an inert row would pass every
 * unit test in the tree and be wrong in the one way that matters -- the reader
 * would keep clicking a row that does nothing.
 */
class NewsChannelRenderTest {

    @AfterTest fun tearDown() = stopKoin()

    private val entries = listOf(
        NewsItem(id = 1, title = "Обновление сервера до 1.21.1", dateEpochSeconds = 1787227200L),
        NewsItem(id = 2, title = "Ивент на выходных: постройка города", dateEpochSeconds = 1787140800L),
        NewsItem(id = 3, title = "Технические работы в ночь на среду", dateEpochSeconds = 1787054400L),
        NewsItem(id = 4, title = "Правила обновлены, читайте внимательно", dateEpochSeconds = 1786968000L),
    )

    private class FakeFeed(
        private val items: List<NewsItem>,
        override val policy: NewsChannelPolicy,
    ) : INewsFeed {
        override suspend fun page(page: Int, forceRefresh: Boolean): NewsPage =
            if (page > 1) NewsPage(page = page, totalPages = 1)
            else NewsPage(items = items, page = 1, totalPages = 1)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, dark: Boolean, body: @Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((320 * d).toInt(), (300 * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(useDarkTheme = dark) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s8),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Box(Modifier.width(300.dp)) { body() }
                    }
                }
            }
        }
        var t = 0L
        var img = scene.render(t)
        // The feed is fetched in an effect, so the first frame is the skeleton.
        repeat(30) {
            t += 16_000_000L
            img = scene.render(t)
        }
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/news-$name.png").writeBytes(it)
        }
    }

    private fun koin() = startKoin {
        modules(module { single { ServerProtocolConfig() } })
    }

    @Test
    fun channels() {
        koin()
        for (dark in listOf(true, false)) {
            val suffix = if (dark) "dark" else "light"
            // The launcher's own feed: dated rows that open at the site.
            sheet("upstream-$suffix", dark) {
                CompactNewsFeed(
                    maxItems = 4,
                    feed = FakeFeed(entries, NewsChannelPolicy()),
                )
            }
            // The alternate: the same entries as inert text, no arrow to click.
            sheet("alternate-$suffix", dark) {
                CompactNewsFeed(
                    maxItems = 4,
                    feed = FakeFeed(
                        entries,
                        NewsChannelPolicy(opensSource = false, order = NewsOrder.Sample),
                    ),
                )
            }
        }
        // The curated pool, read from the bundled resource rather than a copy of it,
        // so the sheet shows what actually ships and a line too long for the row
        // shows up here instead of in the running launcher. An empty temp dir means
        // no on-disk override, so the jar's own pool is what loads.
        val emptyDir = createTempDirectory("curated-render")
        for (dark in listOf(true, false)) {
            sheet("curated-${if (dark) "dark" else "light"}", dark) {
                CompactNewsFeed(maxItems = 5, feed = CuratedNewsFeed(emptyDir))
            }
        }

        // The alternate with no address: a sentence, and deliberately no retry.
        sheet("alternate-unconfigured", true) {
            CompactNewsFeed(
                maxItems = 4,
                feed = FakeFeed(emptyList(), NewsChannelPolicy(opensSource = false)),
                unavailable = "Адрес ленты не задан",
            )
        }
    }
}
