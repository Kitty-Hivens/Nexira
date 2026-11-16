package hivens.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A test's folder is deleted only once the work the test started has let go of it. Linux deletes
 * a folder around an open file, so the delete here refuses the way Windows does: while a file under
 * the folder is open.
 */
@OptIn(ExperimentalPathApi::class)
class TestFoldersTest {

    private val dir: Path = Files.createTempDirectory("test-folders")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Set while the coroutine below has its file open.
    private val open = AtomicBoolean(false)
    private val opened = CountDownLatch(1)
    private val refusals = AtomicInteger()

    /** A delete with the rule Windows has: nothing goes while a file in the folder is open. */
    private val windowsDelete: (Path) -> Unit = { path ->
        if (open.get()) {
            refusals.incrementAndGet()
            throw FileSystemException(path.toString(), null, "The process cannot access the file because it is being used by another process")
        }
        path.deleteRecursively()
    }

    // Plain coroutines here rather than the helper under test, so a broken helper cannot hide behind its own teardown.
    @AfterTest
    fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    /** Holds a file in [dir] open for [holdMs], in a write that cancellation does not cut short. */
    private fun holdAFileOpen(holdMs: Long) {
        scope.launch {
            try {
                FileChannel.open(dir.resolve("settings.json"), CREATE, WRITE).use {
                    open.set(true)
                    opened.countDown()
                    Thread.sleep(holdMs)
                }
            } finally {
                open.set(false)
            }
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS), "the coroutine never opened its file")
    }

    @Test
    fun `cancelling alone deletes the folder while a coroutine still has a file in it open`() {
        holdAFileOpen(holdMs = 1_000)

        scope.cancel()

        assertFailsWith<FileSystemException>("the rule this test stands in for Windows' did not hold") { windowsDelete(dir) }
    }

    @Test
    fun `the folder is deleted once the coroutine holding a file in it has finished, and not before`() {
        holdAFileOpen(holdMs = 1_000)

        stopThenDelete(scope, dir, delete = windowsDelete)

        assertFalse(dir.exists(), "the folder is still there")
        assertEquals(0, refusals.get(), "the delete ran while the file was open, and only trying again saved it")
    }

    @Test
    fun `what is settled runs after the coroutines stopped and before the delete`() {
        val order = CopyOnWriteArrayList<String>()
        holdAFileOpen(holdMs = 300)
        val stopped = { scope.coroutineContext.job.isCompleted && !open.get() }

        stopThenDelete(
            scope,
            dir,
            settle = { order += if (stopped()) "settled" else "settled while running" },
            delete = { path ->
                order += "deleted"
                windowsDelete(path)
            },
        )

        assertEquals(listOf("settled", "deleted"), order.toList())
    }

    @Test
    fun `a file let go a moment after the work ends is waited out by trying again`() {
        val tries = AtomicInteger()

        deleteOnceReleased(dir, pause = 10.milliseconds) { path ->
            if (tries.incrementAndGet() <= 3) throw FileSystemException(path.toString())
            path.deleteRecursively()
        }

        assertEquals(4, tries.get())
        assertFalse(dir.exists())
    }

    @Test
    fun `a file never let go fails the teardown with the last refusal`() {
        val tries = AtomicInteger()

        val failure = assertFailsWith<FileSystemException> {
            deleteOnceReleased(dir, tries = 5, pause = 1.milliseconds) { path ->
                throw FileSystemException(path.toString(), null, "refusal ${tries.incrementAndGet()}")
            }
        }

        assertEquals(5, tries.get())
        assertEquals("refusal 5", failure.reason)
    }

    @Test
    fun `a coroutine that will not stop fails the teardown, naming it, instead of hanging it`() {
        val release = CountDownLatch(1)
        scope.launch { release.await(30, TimeUnit.SECONDS) }
        try {
            val started = System.nanoTime()
            val failure = assertFailsWith<AssertionError> { scope.stopAndAwait(timeout = 200.milliseconds) }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(tookMs < 5_000, "the teardown waited $tookMs ms on a 200 ms limit")
            assertTrue("StandaloneCoroutine" in failure.message.orEmpty(), "the coroutine still running is not named: ${failure.message}")
        } finally {
            release.countDown()
        }
    }
}
