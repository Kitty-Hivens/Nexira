package hivens.launcher.news

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.INewsFeed
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache
import hivens.core.cache.read
import hivens.core.data.NewsChannelPolicy
import hivens.core.data.NewsOrder
import hivens.core.data.NewsPage
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * A second news channel, read from any RSS or Atom address the user names.
 *
 * The launcher's own feed is one site's archive, on a path that is being retired,
 * and it is the only thing [INewsFeed] has ever had behind it. This is the other
 * shape: a channel whose contents the launcher makes no claim about. That is what
 * its [policy] says -- the rows do not open at their source and nothing but their
 * text arrives -- and it is why [SyndicationParser] throws the link and the images
 * away rather than trusting a surface to leave them alone.
 *
 * **It makes no request until the user has named an address.** There is no default
 * URL, and there is no request at startup: [url] is read per fetch, so a channel
 * nobody configured costs nothing and reaches nowhere. When one is configured, the
 * whole channel is one GET to that host and no other.
 *
 * The address itself is checked rather than trusted. It comes out of a settings
 * file a person edits by hand, and only http and https are honoured: a `file:` or
 * `jar:` address in that field would otherwise turn a news rail into a reader of
 * the local disk.
 */
class SyndicationNewsFeed(
    private val clientProvider: HttpClientProvider,
    /** The configured address, read per fetch. Null or blank = no channel. */
    private val url: () -> String?,
    private val cache: Cache<NewsPage> = PassthroughCache(),
) : INewsFeed {

    private val log = LoggerFactory.getLogger(SyndicationNewsFeed::class.java)

    override val policy = NewsChannelPolicy(opensSource = false, order = NewsOrder.Sample)

    /**
     * True once an address this may fetch has been configured.
     *
     * Read by the surface so an unconfigured channel says what is missing rather
     * than showing an empty rail with a retry behind it: there is nothing to
     * retry, and the difference between "the feed is empty" and "no feed was ever
     * named" is the whole of what the reader needs to know.
     */
    val configured: Boolean get() = validated(url()) != null

    /**
     * The whole channel, as one page.
     *
     * A syndication document is not paged: it is the last N entries the publisher
     * chose to expose, all of them in one response. So page 1 is the feed and
     * there is nothing after it -- a surface walking the archive stops at the end
     * of what arrived rather than asking for a page 2 that would fetch the same
     * document again.
     */
    override suspend fun page(page: Int, forceRefresh: Boolean): NewsPage {
        if (page > 1) return NewsPage(page = page, totalPages = 1)
        val address = validated(url()) ?: return NewsPage()
        return cache.read("feed:$address", forceRefresh) { load(address) }
    }

    /**
     * Off the caller's thread: the caller is a composition effect, and both the
     * body read and the parse over a whole document would otherwise run on the UI
     * thread. A cancellation is re-thrown rather than reported as a failed read --
     * caught and turned into an empty page it would be indistinguishable from an
     * outage, and the cache would hand that nothing to everyone waiting on it.
     */
    private suspend fun load(address: String): NewsPage = withContext(Dispatchers.IO) {
        runCatching {
            val document = fetch(address)
            SyndicationParser.parse(document).also {
                if (it.items.isEmpty()) log.warn("Alternate news feed parsed to nothing: {}", address)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            log.warn("Alternate news feed could not be read: {}", address, it)
        }.getOrDefault(NewsPage())
    }

    /**
     * The document, up to [MAX_BYTES].
     *
     * Bounded rather than read whole: the address is arbitrary, so the response
     * size is somebody else's decision, and a feed that answers a gigabyte would
     * otherwise be held in memory in full before anything looked at it. A
     * truncated document simply parses to the entries that made it in.
     */
    private suspend fun fetch(address: String): String =
        clientProvider.current.prepareGet(address).execute { response ->
            if (!response.status.isSuccess()) {
                error("GET $address -> HTTP ${response.status}")
            }
            val channel = response.bodyAsChannel()
            val sink = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (sink.size() < MAX_BYTES) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                sink.write(buffer, 0, minOf(read, MAX_BYTES - sink.size()))
            }
            sink.toString(Charsets.UTF_8)
        }

    /**
     * The address if it is one this may fetch, null otherwise.
     *
     * An empty field is the ordinary state of this setting and says nothing; an
     * address of the wrong kind is worth a line in the log, because from the
     * reader's side it looks exactly like a feed that will not load.
     */
    private fun validated(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = runCatching { URI(trimmed).scheme }.getOrNull()?.lowercase()
        if (scheme != "http" && scheme != "https") {
            log.warn("Alternate news feed address is not an http(s) URL, ignoring it")
            return null
        }
        return trimmed
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024

        /** Generous for a feed of headlines, and a ceiling on what one can cost. */
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
