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

    // --- staging: the swap runs after the process is told to exit, so what it
    // costs is what the user watches a dead window for ---

    @Test
    fun `a download already at the staging path is installed without a copy`() {
        val exe = file("Nexira.AppImage", "OLD")
        val staged = file("Nexira.AppImage.new", "NEW")

        applicator.swapBinary(staged, exe, exe, dir.resolve("Nexira.AppImage.backup"))

        assertEquals("NEW", Files.readString(exe))
        assertFalse(
            Files.exists(staged),
            "the staged image is the one that was moved into place -- a copy would have left it behind",
        )
    }

    @Test
    fun `the backup does not cost a second copy of the image`() {
        // The renamed-target case, because it is the one where both names
        // survive the swap and can be compared. Copying 77MB here is copying it
        // with the process already told to exit.
        val exe = file("Nexira-2.3.0.AppImage", "OLD")
        val target = dir.resolve("Nexira-2.3.1.AppImage")
        val backup = dir.resolve("Nexira-2.3.0.AppImage.backup")

        applicator.swapBinary(file("Nexira-2.3.1.AppImage.new", "NEW"), exe, target, backup)

        assertEquals("OLD", Files.readString(backup))
        assertTrue(
            Files.isSameFile(exe, backup),
            "the backup is another name for the bytes already on disk, not a second copy of them",
        )
    }

    @Test
    fun `the staging path puts the download beside the binary it replaces`() {
        val exe = dir.resolve("Nexira-2.3.0-x86_64.AppImage")
        assertEquals(
            dir.resolve("Nexira-2.3.1-x86_64.AppImage.new"),
            applicator.stagedPathFor(exe, fallbackDir = dir.resolve("updates"), fileName = "Nexira-2.3.1-x86_64.AppImage"),
        )
    }

    @Test
    fun `an install directory that cannot be written falls back to the updates directory`() {
        if (!dir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        val installDir = Files.createDirectory(dir.resolve("readonly"))
        val updates = dir.resolve("updates")
        Files.setPosixFilePermissions(installDir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            assertEquals(
                updates.resolve("Nexira-2.3.1-x86_64.AppImage"),
                applicator.stagedPathFor(
                    installDir.resolve("Nexira-2.3.0-x86_64.AppImage"),
                    fallbackDir = updates,
                    fileName = "Nexira-2.3.1-x86_64.AppImage",
                ),
                "a launcher the user cannot write next to still updates, it just pays for the copy",
            )
        } finally {
            Files.setPosixFilePermissions(installDir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test
    fun `the staging suffix does not decide the target`() {
        // The target comes from what was published. Reading it off the path this
        // class chose for the download would install every update over the
        // running binary instead of beside it.
        val exe = dir.resolve("Nexira-2.3.0-x86_64.AppImage")
        val staged = dir.resolve("Nexira-2.3.1-x86_64.AppImage.new")

        assertEquals("Nexira-2.3.1-x86_64.AppImage", applicator.assetNameOf(staged))
        assertEquals(
            dir.resolve("Nexira-2.3.1-x86_64.AppImage"),
            applicator.targetFor(exe, applicator.assetNameOf(staged)),
        )
    }

    @Test
    fun `rolling back a renamed update leaves no backup beside the launcher`() {
        // The backup and the launcher are one file here -- the update installed
        // under a different name, so the old binary was never replaced. Renaming
        // one onto the other succeeds and does nothing, and the caller would
        // walk away believing the backup had been consumed.
        val exe = file("Nexira-2.3.0.AppImage", "OLD")
        val backup = dir.resolve("Nexira-2.3.0.AppImage.backup")
        applicator.swapBinary(file("Nexira-2.3.1.AppImage.new", "NEW"), exe, dir.resolve("Nexira-2.3.1.AppImage"), backup)

        applicator.restoreBackup(backup, exe)

        assertEquals("OLD", Files.readString(exe), "the rollback must leave a launcher at the path it was started from")
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `rolling back an in-place update restores the binary that was replaced`() {
        val exe = file("Nexira.AppImage", "OLD")
        val backup = dir.resolve("Nexira.AppImage.backup")
        applicator.swapBinary(file("Nexira.AppImage.new", "NEW"), exe, exe, backup)

        applicator.restoreBackup(backup, exe)

        assertEquals("OLD", Files.readString(exe))
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `a download the user never installed is swept`() {
        file("Nexira-2.3.0-x86_64.AppImage", "RUNNING")
        file("Nexira-2.3.1-x86_64.AppImage.new", "NEVER INSTALLED")
        file("notes.txt", "keep me")

        assertEquals(
            listOf(dir.resolve("Nexira-2.3.1-x86_64.AppImage.new")),
            applicator.leftoversIn(dir),
            "one staged image per version checked would otherwise pile up beside the launcher",
        )
    }
}
