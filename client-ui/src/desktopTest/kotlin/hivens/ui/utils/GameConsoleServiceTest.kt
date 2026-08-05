package hivens.ui.utils

import hivens.launcher.platform.PlatformPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the off-thread drainer: enqueue on the test thread, await the
 * coalesced [ConsoleSnapshot] the service publishes from its IO drainer. Proves
 * ingestion + the sliding window + file mirroring all work without the UI.
 */
class GameConsoleServiceTest {

    private lateinit var dir: Path
    private val services = mutableListOf<GameConsoleService>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("console-svc-")
    }

    /**
     * Close every service before deleting the tree: a started session leaves the
     * log file open, and Windows will not delete a file that still has a handle.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        runBlocking { services.forEach { it.close() } }
        services.clear()
        dir.deleteRecursively()
    }

    private fun service(maxLines: Int = 5000): GameConsoleService {
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        return GameConsoleService(paths).also {
            it.maxLines = maxLines
            services += it
        }
    }

    private suspend fun GameConsoleService.awaitSnapshot(
        predicate: (ConsoleSnapshot) -> Boolean,
    ): ConsoleSnapshot = withTimeout(3.seconds) { snapshot.first(predicate) }

    @Test
    fun `append publishes the line off-thread`() = runBlocking {
        val svc = service()
        svc.append("hello")
        val snap = svc.awaitSnapshot { s -> s.entries.any { it.text == "hello" } }
        assertTrue(snap.entries.any { it.text == "hello" })
    }

    @Test
    fun `sliding window keeps the last maxLines in order`() = runBlocking {
        val svc = service(maxLines = 5)
        repeat(12) { svc.append("line-$it") }
        val snap = svc.awaitSnapshot { it.entries.size == 5 && it.entries.last().text == "line-11" }
        assertEquals(
            listOf("line-7", "line-8", "line-9", "line-10", "line-11"),
            snap.entries.map { it.text },
            "buffer keeps the latest maxLines in arrival order",
        )
        assertEquals(7, snap.historyOffset, "dropped entries are counted for history paging")
    }

    @Test
    fun `appendOrUpdate collapses a slot to a single line`() = runBlocking {
        val svc = service()
        svc.appendOrUpdate("p", "1/3")
        svc.appendOrUpdate("p", "2/3")
        svc.appendOrUpdate("p", "3/3")
        val snap = svc.awaitSnapshot { it.entries.size == 1 && it.entries[0].text == "3/3" }
        assertEquals(1, snap.entries.size, "progress ticks overwrite one line, not append three")
        assertEquals("3/3", snap.entries[0].text)
    }

    @Test
    fun `a progress line ageing out does not shift the history window`() = runBlocking {
        // The window pages history back by FILE LINE index, and a slot line (a
        // provisioning tick) is deliberately never mirrored to the file. One that
        // ages out of the window must therefore not advance the offset.
        val svc = service(maxLines = 4)
        svc.startSession("Test")
        svc.appendOrUpdate("prep", "preparing 1/2")
        repeat(6) { svc.append("line-$it") }

        val snap = svc.awaitSnapshot { it.entries.size == 4 && it.entries.last().text == "line-5" }
        assertEquals(listOf("line-2", "line-3", "line-4", "line-5"), snap.entries.map { it.text })
        assertEquals(3, snap.historyOffset, "three MIRRORED lines aged out: the divider, line-0 and line-1")

        val paged = svc.loadHistoryBefore(3)
        assertEquals(3, paged.size)
        assertEquals(
            listOf("line-0", "line-1"), paged.drop(1).map { it.text },
            "history pages in the lines immediately before the window, not a shifted slice of the file",
        )
        assertTrue(paged.first().text.contains("Session started"), "and reaches back to the session divider")
    }

    @Test
    fun `session file persists every appended line`() = runBlocking {
        val svc = service()
        svc.startSession("Test")
        repeat(3) { svc.append("l$it") }
        svc.awaitSnapshot { s -> s.entries.count { it.text.startsWith("l") } == 3 }

        val file = assertNotNull(svc.capturedSessionFiles("Test").firstOrNull(), "a per-session file is opened")
        assertTrue(file.exists(), "the session file is written to disk")
        val lines = file.readLines()
        assertTrue(lines.any { it.contains("Session started") }, "divider is mirrored")
        assertTrue(lines.any { it.endsWith("l0") } && lines.any { it.endsWith("l2") }, "all lines flushed")
    }

    @Test
    fun `close flushes the queue then releases the session file`() = runBlocking {
        val svc = service()
        svc.startSession("Test")
        repeat(3) { svc.append("l$it") }
        svc.close()

        val file = assertNotNull(svc.capturedSessionFiles("Test").firstOrNull())
        val afterClose = file.readLines()
        assertTrue(afterClose.any { it.endsWith("l2") }, "lines queued before close still reach disk")

        // The writer is gone, so a later append cannot grow the file -- which is
        // what lets the caller delete the tree underneath it.
        svc.append("after-close")
        assertEquals(afterClose, file.readLines(), "nothing is written once closed")
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val svc = service()
        svc.startSession("Test")
        svc.close()
        svc.close()
    }

    @Test
    fun `clear empties the buffer`() = runBlocking {
        val svc = service()
        svc.append("x")
        svc.awaitSnapshot { it.entries.isNotEmpty() }
        svc.clear()
        val snap = svc.awaitSnapshot { it.entries.isEmpty() }
        assertTrue(snap.entries.isEmpty())
    }
}
