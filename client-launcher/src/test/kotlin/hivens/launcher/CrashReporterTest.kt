package hivens.launcher

import hivens.core.diag.ActionRing
import hivens.launcher.platform.PlatformPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crash report is the only forensic artifact when the launcher dies, so its
 * generation and on-disk layout are pinned: the throwable, the thread, and the
 * environment must all survive into a parseable file under the crash dir.
 */
class CrashReporterTest {

    private lateinit var home: Path
    private lateinit var reporter: CrashReporter
    private lateinit var paths: PlatformPaths

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("crash-reporter-test-")
        paths = PlatformPaths("Linux", home, { null }, { null })
        reporter = CrashReporter(paths)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun `generate captures the throwable, thread, and a non-blank environment`() {
        val boom = IllegalStateException("kaboom-marker")
        val report = reporter.generate(boom, Thread.currentThread())

        assertTrue(report.stackTrace.contains("kaboom-marker"), "message must reach the stack trace")
        assertTrue(report.stackTrace.contains("IllegalStateException"), "exception type must be recorded")
        assertEquals(Thread.currentThread().name, report.thread)
        assertTrue(report.osName.isNotBlank())
        assertTrue(report.jvmVersion.isNotBlank())
        assertTrue(report.maxMemoryMb > 0, "max memory should be a positive MB count")
        // timestamp is an ISO instant the saver re-parses; it must round-trip.
        java.time.Instant.parse(report.timestamp)
    }

    @Test
    fun `saveToDisk writes a crash file under the crash dir with the report contents`() {
        val report = reporter.generate(RuntimeException("disk-marker"), Thread.currentThread())
        val file = reporter.saveToDisk(report)

        assertTrue(file.exists(), "crash file must be written")
        assertEquals(paths.crashDir, file.toPath().parent, "must land in the crash dir")
        assertTrue(file.name.startsWith("crash-") && file.name.endsWith(".txt"), "name: ${file.name}")

        val text = file.readText()
        assertTrue(text.contains("Nexira Crash Report"), "header present")
        assertTrue(text.contains(report.version))
        assertTrue(text.contains(report.thread))
        assertTrue(text.contains("disk-marker"), "stack trace embedded")
    }

    @Test
    fun `saveToDisk creates the crash directory when it does not yet exist`() {
        assertTrue(!Files.exists(paths.crashDir), "precondition: crash dir absent")
        val file = reporter.saveToDisk(reporter.generate(RuntimeException("x"), Thread.currentThread()))
        assertTrue(Files.isDirectory(paths.crashDir))
        assertTrue(file.exists())
    }

    @Test
    fun `saveToDisk renders the empty-actions placeholder`() {
        ActionRing.clear()
        val report = reporter.generate(RuntimeException("x"), Thread.currentThread())
        val text = reporter.saveToDisk(report).readText()
        if (report.actions.isEmpty()) {
            assertTrue(text.contains("(none recorded)"), "empty action ring should render the placeholder")
        }
    }
}
