package hivens.launcher.news

import hivens.core.api.interfaces.IServerListService
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import hivens.test.MockResponse
import hivens.test.buildMockClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartyCraftNewsFeedTest {

    private val config = ServerProtocolConfig(baseUrl = "https://www.example.invalid")

    private class FakeDashboard(private val news: List<NewsItem>) : IServerListService {
        var reads = 0
            private set

        override fun fetchDashboardData(): CompletableFuture<DashboardData> {
            reads++
            return CompletableFuture.completedFuture(DashboardData(emptyList(), news))
        }

        override fun refresh(): CompletableFuture<DashboardData> = fetchDashboardData()
    }

    private fun html(vararg ids: Int, totalPages: Int = 45): String {
        val blocks = ids.joinToString("\n") { id ->
            """
            <div id="news$id" class="content-block np">
                <img src="images/news/n$id.jpg" alt=" " class="news-block-img">
                <div class="news-date-block tip" title="curator<br />1 августа в 00:15"><h1>1</h1><h2>Августа<br />2026</h2></div>
                <h1><a href="news$id#full">Entry $id</a></h1>
                <div class="news-desc"><h1>10 просмотров</h1></div>
            </div>
            """.trimIndent()
        }
        return """<html><body>$blocks<span class="page-total">$totalPages</span></body></html>"""
    }

    private fun feed(
        vararg responses: MockResponse,
        dashboard: IServerListService = FakeDashboard(emptyList()),
    ) = SmartyCraftNewsFeed(
        clientProvider = buildMockClient(*responses),
        config = config,
        dashboard = dashboard,
    )

    @Test
    fun `a page is read off the site index`() = runTest {
        val feed = feed(
            MockResponse(urlContains = "index_page1", body = html(482, 481), contentType = ContentType.Text.Html)
        )

        val page = feed.page(1)

        assertEquals(listOf(482, 481), page.items.map { it.id })
        assertEquals(1, page.page)
        assertEquals(45, page.totalPages)
        assertTrue(page.hasMore, "the archive goes on past the first page")
        assertTrue(!page.fallback, "this is the archive itself")
    }

    @Test
    fun `each page is asked for by its own number`() = runTest {
        val feed = feed(
            MockResponse(urlContains = "index_page3", body = html(462, 461), contentType = ContentType.Text.Html)
        )

        val page = feed.page(3)

        assertEquals(3, page.page)
        assertEquals(listOf(462, 461), page.items.map { it.id })
    }

    @Test
    fun `a first page that cannot be read falls back to the dashboard's three`() = runTest {
        val dashboard = FakeDashboard(listOf(NewsItem(id = 1, title = "From the dashboard")))
        val feed = feed(
            MockResponse(status = HttpStatusCode.InternalServerError, body = "nope", contentType = ContentType.Text.Html),
            dashboard = dashboard,
        )

        val page = feed.page(1)

        assertEquals(listOf(1), page.items.map { it.id })
        assertEquals(1, page.totalPages, "the fallback is one page and there is nothing after it")
        assertTrue(!page.hasMore)
        assertTrue(page.fallback, "and it says it is a floor, not the archive")
        assertEquals(1, dashboard.reads)
    }

    // Markup that moved is the same outage as a dead host: the page parses to
    // nothing, and the launcher still shows what it has always shown.
    @Test
    fun `a first page whose markup moved falls back too`() = runTest {
        val dashboard = FakeDashboard(listOf(NewsItem(id = 2, title = "From the dashboard")))
        val feed = feed(
            MockResponse(body = "<html><body>a redesign</body></html>", contentType = ContentType.Text.Html),
            dashboard = dashboard,
        )

        assertEquals(listOf(2), feed.page(1).items.map { it.id })
        assertEquals(1, dashboard.reads)
    }

    @Test
    fun `a later page that fails does not reach for the fallback`() = runTest {
        val dashboard = FakeDashboard(listOf(NewsItem(id = 3, title = "From the dashboard")))
        val feed = feed(
            MockResponse(status = HttpStatusCode.NotFound, body = "", contentType = ContentType.Text.Html),
            dashboard = dashboard,
        )

        val page = feed.page(4)

        assertTrue(page.items.isEmpty(), "the three headlines are not page four of the archive")
        assertEquals(4, page.page)
        assertTrue(!page.hasMore)
        assertEquals(0, dashboard.reads)
    }

    @Test
    fun `an unreachable site with no dashboard either is an empty feed, not a failure`() = runTest {
        val page = feed(
            MockResponse(status = HttpStatusCode.BadGateway, body = "", contentType = ContentType.Text.Html)
        ).page(1)

        assertTrue(page.items.isEmpty())
    }
}
