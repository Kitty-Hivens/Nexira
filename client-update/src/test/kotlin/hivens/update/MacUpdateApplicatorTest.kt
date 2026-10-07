package hivens.update

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the install script under bash with stand-ins for the macOS tools, so the
 * order it replaces the bundle in is checked on any host with a POSIX shell.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class MacUpdateApplicatorTest {

    private lateinit var root: Path
    private lateinit var bin: Path
    private lateinit var mount: Path
    private lateinit var bundle: Path
    private lateinit var opened: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("mac-update-")
        bin = root.resolve("bin").createDirectories()
        mount = root.resolve("mount").createDirectories()
        opened = root.resolve("opened")
        bundle = root.resolve("Applications").resolve("Nexira.app").createDirectories()
        bundle.resolve("version").writeText("old")
        mount.resolve("Nexira.app").createDirectories().resolve("version").writeText("new")

        stub("hdiutil", "exit 0")
        stub("killall", "exit 0")
        stub("sleep", "exit 0")
        stub("open", "printf '%s' \"$1\" > '$opened'")
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun stub(name: String, body: String) {
        val file = bin.resolve(name)
        file.writeText("#!/bin/sh\n$body\n")
        file.toFile().setExecutable(true)
    }

    private fun run(): Int {
        val script = root.resolve("install.sh").apply { writeText(MacUpdateApplicator.INSTALL_SCRIPT) }
        val process = ProcessBuilder("bash", script.toString())
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("PATH", "$bin:${System.getenv("PATH")}")
                    put("INSTALLER", root.resolve("update.dmg").apply { writeText("dmg") }.toString())
                    put("BUNDLE", bundle.toString())
                    put("SCRIPT", script.toString())
                    put("MOUNT", mount.toString())
                }
            }
            .start()
        process.inputStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script did not finish")
        return process.exitValue()
    }

    @Test
    fun `the new bundle takes the installed one's place and is started`() {
        assertEquals(0, run())

        assertEquals("new", bundle.resolve("version").readText())
        assertFalse(bundle.resolveSibling("Nexira.app.new").exists(), "the staged copy is gone")
        assertFalse(bundle.resolveSibling("Nexira.app.old").exists(), "the old bundle is gone")
        assertEquals(bundle.toString(), opened.readText())
    }

    @Test
    fun `a copy that fails leaves the installed version in place and starts it`() {
        stub("cp", "exit 1")

        assertEquals(1, run())

        assertEquals("old", bundle.resolve("version").readText(), "removed before the copy, there is no launcher left")
        assertFalse(bundle.resolveSibling("Nexira.app.new").exists())
        assertEquals(bundle.toString(), opened.readText(), "the installed version comes back up")
    }
}
