package hivens.launcher.news

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.INewsFeed
import hivens.core.api.interfaces.IServerListService
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache
import hivens.core.cache.read
import hivens.core.data.NewsPage
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory

/**
 * The SmartyCraft news archive, read from the site's own paginated index.
 *
 * The launcher used to show the three entries the dashboard payload carries, so
 * a widget asked for twenty got three; the site paginates the same news ten to a
 * page and keeps the archive, which is where the rest of the feed comes from.
 * Pages are fetched on demand -- one when the rail opens, the next when the
 * reader reaches the end of what is loaded -- so the archive costs nothing until
 * somebody actually walks it.
 *
 * Requests go through the SmartyCraft channel ([HttpClientProvider]'s default,
 * SSL-bypass aware), the same one the dashboard and login use: it is the same
 * host, and a certificate the user has already accepted for it should not have
 * to be accepted again per feature.
 *
 * The dashboard's three stay as the floor. When the site cannot be read at all
 * -- offline, or markup that moved -- the first page falls back to them, so the
 * rail keeps showing what the launcher has always shown instead of going blank.
 */
class SmartyCraftNewsFeed(
    private val clientProvider: HttpClientProvider,
    private val config: ServerProtocolConfig,
    private val dashboard: IServerListService,
    private val cache: Cache<NewsPage> = PassthroughCache(),
) : INewsFeed {

    private val log = LoggerFactory.getLogger(SmartyCraftNewsFeed::class.java)

    override suspend fun page(page: Int, forceRefresh: Boolean): NewsPage {
        val index = page.coerceAtLeast(1)
        val fetched = cache.read("page:$index", forceRefresh) { load(index) }
        if (fetched.items.isNotEmpty()) return fetched
        // Nothing to show and nothing more to ask for: a page past the end of the
        // archive is empty on purpose, and one that failed is empty by accident.
        // Only the first page has somewhere else to look.
        return if (index == 1) dashboardFloor() else fetched
    }

    private suspend fun load(index: Int): NewsPage {
        val url = "${config.baseUrl.trimEnd('/')}/index_page$index"
        return runCatching {
            val html: String = clientProvider.current.get(url).body()
            SmartyNewsParser.parse(html, config.baseUrl, index).also {
                if (it.items.isEmpty()) {
                    log.warn("News page {} parsed to nothing -- upstream markup may have moved", index)
                }
            }
        }.onFailure {
            log.warn("News page {} could not be read", index, it)
        }.getOrDefault(NewsPage(page = index, totalPages = index))
    }

    /**
     * The dashboard's own news, as one page with nothing after it. It is the
     * same three entries the launcher read before the archive was reachable.
     */
    private suspend fun dashboardFloor(): NewsPage =
        runCatching { dashboard.fetchDashboardData().await().news }
            .onFailure { log.warn("News fallback to the dashboard failed", it) }
            .getOrDefault(emptyList())
            .let { NewsPage(items = it, page = 1, totalPages = 1) }
}
