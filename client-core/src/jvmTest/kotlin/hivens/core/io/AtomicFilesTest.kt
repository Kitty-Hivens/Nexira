package hivens.core.io

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("atomic-files-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writeString creates missing parent dirs and round-trips`() {
        val file = dir.resolve("a/b/c/data.json")
        AtomicFiles.writeString(file, "hello world")
        assertTrue(Files.isRegularFile(file))
        assertEquals("hello world", Files.readString(file))
    }

    @Test
    fun `writeString overwrites existing content`() {
        val file = dir.resolve("data.json")
        AtomicFiles.writeString(file, "first")
        AtomicFiles.writeString(file, "second")
        assertEquals("second", Files.readString(file))
    }

    @Test
    fun `writeBytes round-trips binary content`() {
        val file = dir.resolve("blob.bin")
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -128, 42)
        AtomicFiles.writeBytes(file, bytes)
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test
    fun `no orphan tmp file is left after a successful write`() {
        val file = dir.resolve("data.json")
        AtomicFiles.writeString(file, "x")
        assertFalse(Files.exists(file.resolveSibling("data.json.tmp")), "tmp must be moved into place, not left behind")
    }

    /**
     * Two callers publishing one file share a temp path named after it, so without
     * serialisation one renames the other's bytes and the loser renames a path that
     * is no longer there. Nothing is torn either way -- each write is whole -- what
     * breaks is that one of them fails outright.
     */
    @Test
    fun `concurrent writers of one file all publish`() {
        val file = dir.resolve("settings.json")
        val writers = 8
        val rounds = 40
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<String>()

        repeat(writers) { w ->
            pool.execute {
                start.await()
                repeat(rounds) { r ->
                    runCatching { AtomicFiles.writeString(file, "writer-$w-round-$r") }
                        .onFailure { failures += it.toString() }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "writers did not finish")

        assertEquals(emptyList(), failures.toList(), "a write of a file someone else is publishing must not fail")
        assertTrue(Files.readString(file).startsWith("writer-"), "the published file is one writer's whole content")
    }
}
