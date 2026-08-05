package hivens.update

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The swap runs inside a shutdown hook, where the process can be cut short by a
 * reboot or a logout. What matters is that every state it can be interrupted in
 * still leaves a launcher on disk -- the in-process rollback only helps while
 * there is a process left to run it.
 */
class LinuxUpdateApplicatorTest {

    private lateinit var dir: Path
    private val applicator = LinuxUpdateApplicator()

    @BeforeTest
    fun setup() {
        dir = Files.createTempDirectory("nexira-linux-update-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun file(name: String, content: String): Path =
        Files.writeString(dir.resolve(name), content)

    @Test
    fun `same-name update replaces the binary and keeps the old one as backup`() {
        val exe = file("Nexira.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")
        val backup = dir.resolve("Nexira.AppImage.backup")

        applicator.swapBinary(installer, exe, exe, backup)

        assertEquals("NEW", Files.readString(exe))
        assertEquals("OLD", Files.readString(backup))
    }

    @Test
    fun `a renamed update leaves the old binary alone until cleanup`() {
        val exe = file("Nexira-2.3.0.AppImage", "OLD")
        val target = dir.resolve("Nexira-2.3.1.AppImage")
        val installer = file("downloaded.AppImage", "NEW")
        val backup = dir.resolve("Nexira-2.3.0.AppImage.backup")

        applicator.swapBinary(installer, exe, target, backup)

        assertEquals("NEW", Files.readString(target))
        assertEquals("OLD", Files.readString(exe), "the running binary must survive its own update")
        assertEquals("OLD", Files.readString(backup))
    }

    @Test
    fun `a failure before the swap leaves the installed launcher untouched`() {
        // The window that used to cost the user their launcher: the live binary
        // was moved aside first, so anything failing after that -- or the
        // process simply being killed -- left a .backup and nothing to run.
        val exe = file("Nexira.AppImage", "OLD")
        val missing = dir.resolve("never-downloaded.AppImage")
        val backup = dir.resolve("Nexira.AppImage.backup")

        assertFailsWith<NoSuchFileException> { applicator.swapBinary(missing, exe, exe, backup) }

        assertTrue(Files.exists(exe), "the launcher was removed before the replacement existed")
        assertEquals("OLD", Files.readString(exe))
    }

    @Test
    fun `the installed binary is executable`() {
        val exe = file("Nexira.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")

        applicator.swapBinary(installer, exe, exe, dir.resolve("Nexira.AppImage.backup"))

        if (!dir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(exe))
    }

    @Test
    fun `a leftover staging file from an interrupted attempt is overwritten`() {
        val exe = file("Nexira.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")
        file("Nexira.AppImage.new", "JUNK-FROM-A-PREVIOUS-RUN")

        applicator.swapBinary(installer, exe, exe, dir.resolve("Nexira.AppImage.backup"))

        assertEquals("NEW", Files.readString(exe))
        assertFalse(Files.exists(dir.resolve("Nexira.AppImage.new")), "staging file must not be left behind")
    }
}
