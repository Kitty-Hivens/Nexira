package hivens.launcher.news

import hivens.core.data.NewsOrder
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The channel that works with nothing configured.
 *
 * Two things are being pinned. The pool ships in the jar, so a fresh install has
 * something to show without a request; and the on-disk override replaces it, which
 * is what lets the pool grow one line at a time instead of one rebuild at a time.
 */
class CuratedNewsFeedTest {

    private val dir: Path = createTempDirectory("curated-news")

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `the bundled pool is read with nothing configured`() = runTest {
        val page = CuratedNewsFeed(dir).page(1)
        assertTrue(page.items.isNotEmpty(), "the pool must ship with the launcher")
        assertTrue(
            page.items.none { it.title.startsWith("#") || it.title.isBlank() },
            "comments and blank lines are not entries: ${page.items.map { it.title }}",
        )
    }

    @Test
    fun `entries carry no date and no image, so the row draws neither`() = runTest {
        val page = CuratedNewsFeed(dir).page(1)
        assertTrue(page.items.all { it.dateEpochSeconds == 0L })
        assertTrue(page.items.all { it.imageUrl == null && it.thumbnailUrl == null })
    }

    @Test
    fun `the channel opens nowhere and draws at random`() {
        val policy = CuratedNewsFeed(dir).policy
        assertFalse(policy.opensSource, "a curated line has no source to open")
        assertEquals(NewsOrder.Sample, policy.order)
    }

    @Test
    fun `an on-disk pool replaces the bundled one`() = runTest {
        Files.writeString(
            dir.resolve("news-curated.txt"),
            """
            # a comment

            Первая строка
            Вторая строка
            """.trimIndent(),
        )
        val page = CuratedNewsFeed(dir).page(1)
        assertEquals(listOf("Первая строка", "Вторая строка"), page.items.map { it.title })
    }

    /**
     * Re-read rather than cached: the pool is meant to be edited by hand while the
     * launcher runs, and a cache would mean a restart per line.
     */
    @Test
    fun `an edit to the pool shows on the next open`() = runTest {
        val file = dir.resolve("news-curated.txt")
        val feed = CuratedNewsFeed(dir)
        Files.writeString(file, "Одна")
        assertEquals(listOf("Одна"), feed.page(1).items.map { it.title })
        Files.writeString(file, "Одна\nДве")
        assertEquals(listOf("Одна", "Две"), feed.page(1).items.map { it.title })
    }

    @Test
    fun `the same line twice is one row rather than two with one key`() = runTest {
        Files.writeString(dir.resolve("news-curated.txt"), "Одна\nОдна\nДве")
        val page = CuratedNewsFeed(dir).page(1)
        assertEquals(2, page.items.size)
        assertEquals(page.items.map { it.id }.distinct().size, page.items.size)
    }

    @Test
    fun `an empty pool is an empty page rather than a failure`() = runTest {
        Files.writeString(dir.resolve("news-curated.txt"), "# nothing but a comment\n\n")
        val feed = CuratedNewsFeed(dir)
        assertEquals(0, feed.page(1).items.size)
        assertFalse(feed.stocked)
    }

    @Test
    fun `there is no second page to walk`() = runTest {
        val page = CuratedNewsFeed(dir).page(2)
        assertEquals(0, page.items.size)
        assertFalse(page.hasMore)
    }
}
