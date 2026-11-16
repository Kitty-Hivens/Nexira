package hivens.ui

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher writes its logs into the data directory's `logs` folder from the first line,
 * checked on the real entry point in a JVM of its own, started the way a packaged launcher is.
 *
 * Logback reads where to write once, on the first logger anything in the process asks for, and a
 * directory named after that is never seen. A static logger reached while the entry class itself
 * was initialised, before the first line of `main`, sent every log of every session into the
 * fallback folder under the temp directory. Nothing that shares this JVM's logging can see that,
 * since logback is long configured here by the time a test runs, so the launcher is started fresh.
 *
 * The run ends where a second launcher does: this test holds the data directory's lock, so the
 * launcher it starts logs that the lock is taken and exits before any window or toolkit comes up.
 */
class EntryLogDirectoryTest {

    @Test
    fun `the entry point logs into the data directory and not the fallback`() {
        val root = createTempDirectory("entry-logs")
        try {
            val dataDir = root.resolve("data").createDirectories()
            val tmpDir = root.resolve("tmp").createDirectories()
            val output = root.resolve("output.txt")

            val exitCode = FileChannel.open(dataDir.resolve(".lock"), CREATE, READ, WRITE).use { channel ->
                channel.lock().use { startLauncher(root, dataDir, tmpDir, output) }
            }

            val log = dataDir.resolve("logs").resolve("launcher.log")
            assertTrue(
                log.exists(),
                "launcher.log is not in the data directory's logs folder, so something created a logger " +
                    "before the entry point named the directory. Fallback folder holds: " +
                    "${listing(tmpDir.resolve("nexira-fallback-logs"))}. Launcher output:\n${output.readText()}",
            )
            assertContains(log.readText(), "Single-instance lock held by another process")
            assertEquals(0, exitCode, "the launcher should have yielded to the lock holder. Output:\n${output.readText()}")
        } finally {
            val _ = root.toFile().deleteRecursively()
        }
    }

    /** Starts `hivens.ui.MainKt` on this test's classpath and waits for it, returning its exit code. */
    private fun startLauncher(root: Path, dataDir: Path, tmpDir: Path, output: Path): Int {
        // An argument file rather than -cp on the command line, which the classpath of this module
        // outgrows on Windows. Quoted, so a backslash in it is an escape and is doubled.
        val classpath = System.getProperty("java.class.path").replace("\\", "\\\\")
        val args = root.resolve("java.args")
        args.writeText("-cp \"$classpath\"\n")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), "@$args", "-Djava.io.tmpdir=$tmpDir", "hivens.ui.MainKt")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .apply { environment()["NEXIRA_DATA_DIR"] = dataDir.toString() }
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("The launcher did not exit within a minute. Output:\n${output.readText()}")
        }
        return process.exitValue()
    }

    private fun listing(dir: Path): List<String> =
        if (dir.exists()) Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() } else emptyList()
}
