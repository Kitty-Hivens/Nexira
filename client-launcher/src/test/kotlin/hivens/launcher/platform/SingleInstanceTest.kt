package hivens.launcher.platform

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("single-instance-test")
        dataDir.toFile().deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        // Always release between tests — SingleInstance is a static singleton
        // and would carry state across test methods otherwise.
        SingleInstance.release()
    }

    @Test
    fun `acquire on a clean dir returns true and writes pid`() {
        assertTrue(SingleInstance.acquire(dataDir), "first acquire on a fresh dir must succeed")

        // Lock file itself is held exclusively (mandatory on Windows) — read
        // the diagnostic PID from the side-car .lock.pid file instead.
        assertTrue(Files.exists(dataDir.resolve(".lock")))
        val pidLine = Files.readString(dataDir.resolve(".lock.pid")).trim()
        assertContains(
            pidLine,
            ProcessHandle.current().pid().toString(),
            message = ".lock.pid must contain our pid for diagnostics"
        )
    }

    @Test
    fun `release is idempotent`() {
        SingleInstance.acquire(dataDir)
        SingleInstance.release()
        SingleInstance.release()  // must not throw
    }

    @Test
    fun `acquire returns false and writes show signal when lock already held`() {
        // Simulate a foreign holder by opening the lock file ourselves and
        // taking its FileLock in this process — SingleInstance.acquire then
        // sees it from the same JVM and returns false.
        val lockFile = dataDir.resolve(".lock")
        val foreignChannel = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        val foreignLock = foreignChannel.tryLock()
        try {
            assertFalse(
                SingleInstance.acquire(dataDir),
                "second acquire must report failure when another holder has the lock"
            )
            assertTrue(
                Files.exists(dataDir.resolve(".show")),
                ".show signal must be written so the running instance raises its window"
            )
        } finally {
            foreignLock?.release()
            foreignChannel.close()
        }
    }

    @Test
    fun `acquire after release reacquires the lock`() {
        assertTrue(SingleInstance.acquire(dataDir))
        SingleInstance.release()
        assertTrue(
            SingleInstance.acquire(dataDir),
            "released lock must be reacquirable in the same process"
        )
    }
}
