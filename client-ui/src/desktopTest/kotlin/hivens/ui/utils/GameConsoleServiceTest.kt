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

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("console-svc-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun service(maxLines: Int = 5000): GameConsoleService {
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        return GameConsoleService(paths).also { it.maxLines = maxLines }
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
    fun `clear empties the buffer`() = runBlocking {
        val svc = service()
        svc.append("x")
        svc.awaitSnapshot { it.entries.isNotEmpty() }
        svc.clear()
        val snap = svc.awaitSnapshot { it.entries.isEmpty() }
        assertTrue(snap.entries.isEmpty())
    }
}
