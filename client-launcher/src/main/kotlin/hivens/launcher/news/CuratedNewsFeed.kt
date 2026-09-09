package hivens.launcher.news

import hivens.core.api.interfaces.INewsFeed
import hivens.core.data.NewsChannelPolicy
import hivens.core.data.NewsItem
import hivens.core.data.NewsOrder
import hivens.core.data.NewsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A channel with no source at all: a pool of lines that ships with the launcher.
 *
 * It exists because a live feed cannot be shipped as a default. A syndication
 * address in the box would make every install fetch a stranger's document on its
 * first run, and whatever arrived would be whatever that site published that
 * morning, at that site's own idea of how to present it. This channel is the other
 * end: no request, nothing to configure, and what it shows is a deliberate
 * selection in the repository rather than today's front page.
 *
 * The pool is [POOL_RESOURCE], and `<dataDir>/news-curated.txt` replaces it when
 * present. The override is the point of the format: the pool is meant to grow one
 * line at a time, and a rebuild per line is what stops that happening. It is
 * re-read per open rather than cached, because a file edited by hand while the
 * launcher runs should show up on the next look at the rail.
 *
 * One line per entry, blank lines and `#` comments skipped. No dates and no links:
 * these are not news items, they are lines, and the row draws neither when they are
 * absent. The order is a random draw ([NewsOrder.Sample]) so a pool that never
 * changes does not park the same few lines at the top of the rail for weeks.
 */
class CuratedNewsFeed(private val dataDir: Path) : INewsFeed {

    private val log = LoggerFactory.getLogger(CuratedNewsFeed::class.java)

    override val policy = NewsChannelPolicy(opensSource = false, order = NewsOrder.Sample)

    /** The whole pool, as one page: there is no archive to walk. */
    override suspend fun page(page: Int, forceRefresh: Boolean): NewsPage {
        if (page > 1) return NewsPage(page = page, totalPages = 1)
        val lines = withContext(Dispatchers.IO) { load() }
        return NewsPage(
            items = lines.map { NewsItem(id = it.hashCode(), title = it) }.distinctBy { it.id },
            page = 1,
            totalPages = 1,
        )
    }

    /** True when the pool has anything in it, so a surface need not draw an empty rail. */
    val stocked: Boolean get() = runCatching { load().isNotEmpty() }.getOrDefault(false)

    private fun load(): List<String> {
        val override = dataDir.resolve(OVERRIDE_FILE)
        val text = if (Files.isRegularFile(override)) {
            runCatching { Files.readString(override) }
                .onFailure { log.warn("Curated news pool at {} could not be read", override, it) }
                .getOrNull()
        } else {
            javaClass.getResourceAsStream(POOL_RESOURCE)?.use { it.readBytes().decodeToString() }
        }
        if (text == null) {
            log.warn("Curated news pool is neither overridden nor bundled")
            return emptyList()
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
    }

    private companion object {
        const val POOL_RESOURCE = "/news/curated.txt"
        const val OVERRIDE_FILE = "news-curated.txt"
    }
}
