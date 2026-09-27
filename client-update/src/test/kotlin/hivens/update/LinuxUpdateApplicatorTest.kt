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
    fun `an update replaces the binary and keeps the old one as backup`() {
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")
        val backup = dir.resolve("Nexira-x86_64.AppImage.backup")

        applicator.swapBinary(installer, exe, backup)

        assertEquals("NEW", Files.readString(exe))
        assertEquals("OLD", Files.readString(backup))
    }

    /**
     * A store renamed the file on install and remembers it by that path. The
     * update has to land there, or the store is left pointing at a file that
     * is gone while the new one sits beside it under a name it never saw.
     */
    @Test
    fun `an update keeps whatever name the running binary has`() {
        val exe = file("Nexira-x86_64_0123456789abcdef.AppImage", "OLD")
        val staged = applicator.stagedPathFor(exe, fallbackDir = dir.resolve("updates"), fileName = "Nexira-x86_64.AppImage")
        Files.writeString(staged, "NEW")

        applicator.swapBinary(staged, exe, dir.resolve("Nexira-x86_64_0123456789abcdef.AppImage.backup"))

        assertEquals("NEW", Files.readString(exe))
        assertFalse(Files.exists(dir.resolve("Nexira-x86_64.AppImage")), "the published name must not appear beside it")
    }

    /**
     * A nightly kept beside a release. Updating one must not write into the
     * other, whichever of the two published names the download carries.
     */
    @Test
    fun `an update leaves another copy in the same folder alone`() {
        val release = file("Nexira-x86_64.AppImage", "RELEASE")
        val nightly = file("Nexira-nightly-x86_64.AppImage", "OLD NIGHTLY")
        val staged = applicator.stagedPathFor(nightly, fallbackDir = dir.resolve("updates"), fileName = "Nexira-nightly-x86_64.AppImage")
        Files.writeString(staged, "NEW NIGHTLY")

        applicator.swapBinary(staged, nightly, dir.resolve("Nexira-nightly-x86_64.AppImage.backup"))

        assertEquals("NEW NIGHTLY", Files.readString(nightly))
        assertEquals("RELEASE", Files.readString(release))
    }

    @Test
    fun `a failure before the swap leaves the installed launcher untouched`() {
        // The window that used to cost the user their launcher: the live binary
        // was moved aside first, so anything failing after that -- or the
        // process simply being killed -- left a .backup and nothing to run.
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val missing = dir.resolve("never-downloaded.AppImage")
        val backup = dir.resolve("Nexira-x86_64.AppImage.backup")

        assertFailsWith<NoSuchFileException> { applicator.swapBinary(missing, exe, backup) }

        assertTrue(Files.exists(exe), "the launcher was removed before the replacement existed")
        assertEquals("OLD", Files.readString(exe))
    }

    @Test
    fun `the installed binary is executable`() {
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")

        applicator.swapBinary(installer, exe, dir.resolve("Nexira-x86_64.AppImage.backup"))

        if (!dir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(exe))
    }

    @Test
    fun `a leftover staging file from an interrupted attempt is overwritten`() {
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val installer = file("downloaded.AppImage", "NEW")
        file("Nexira-x86_64.AppImage.new", "JUNK-FROM-A-PREVIOUS-RUN")

        applicator.swapBinary(installer, exe, dir.resolve("Nexira-x86_64.AppImage.backup"))

        assertEquals("NEW", Files.readString(exe))
        assertFalse(Files.exists(dir.resolve("Nexira-x86_64.AppImage.new")), "staging file must not be left behind")
    }

    // --- staging: the swap runs after the process is told to exit, so what it
    // costs is what the user watches a dead window for ---

    @Test
    fun `the path the download is given is the path the install moves from`() {
        // The two halves have to name the same file or the install copies the
        // image after the process has been told to exit, which is the whole
        // point of choosing the download destination. Note this is what pins the
        // saving: a copy of a file onto itself is a no-op, so no assertion about
        // the file can tell a redundant copy from a skipped one.
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val staged = applicator.stagedPathFor(exe, fallbackDir = dir.resolve("updates"), fileName = "Nexira-x86_64.AppImage")
        Files.writeString(staged, "NEW")

        applicator.swapBinary(staged, exe, dir.resolve("Nexira-x86_64.AppImage.backup"))

        assertEquals("NEW", Files.readString(exe))
        assertFalse(Files.exists(staged), "the staged image is the one that moved into place")
    }

    @Test
    fun `the backup does not cost a second copy of the image`() {
        // Copying 77MB here is copying it with the process already told to exit.
        // The swap only replaces the name, so the backup still holds the inode
        // the old name pointed at.
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val oldInode = if (dir.fileSystem.supportedFileAttributeViews().contains("unix")) {
            Files.getAttribute(exe, "unix:ino")
        } else null
        val backup = dir.resolve("Nexira-x86_64.AppImage.backup")

        applicator.swapBinary(file("Nexira-x86_64.AppImage.new", "NEW"), exe, backup)

        assertEquals("OLD", Files.readString(backup))
        if (oldInode != null) {
            assertEquals(
                oldInode,
                Files.getAttribute(backup, "unix:ino"),
                "the backup is another name for the bytes already on disk, not a second copy of them",
            )
        }
    }

    @Test
    fun `the staging path puts the download beside the binary it replaces`() {
        val exe = dir.resolve("Nexira-x86_64_0123456789abcdef.AppImage")
        assertEquals(
            dir.resolve("Nexira-x86_64_0123456789abcdef.AppImage.new"),
            applicator.stagedPathFor(exe, fallbackDir = dir.resolve("updates"), fileName = "Nexira-x86_64.AppImage"),
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
                updates.resolve("Nexira-x86_64.AppImage"),
                applicator.stagedPathFor(
                    installDir.resolve("Nexira-x86_64.AppImage"),
                    fallbackDir = updates,
                    fileName = "Nexira-x86_64.AppImage",
                ),
                "a launcher the user cannot write next to still updates, it just pays for the copy",
            )
        } finally {
            Files.setPosixFilePermissions(installDir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test
    fun `rolling back restores the binary that was replaced`() {
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val backup = dir.resolve("Nexira-x86_64.AppImage.backup")
        applicator.swapBinary(file("Nexira-x86_64.AppImage.new", "NEW"), exe, backup)

        applicator.restoreBackup(backup, exe)

        assertEquals("OLD", Files.readString(exe))
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `rolling back before the swap happened leaves no backup beside the launcher`() {
        // The backup and the launcher are one file here: the backup was linked
        // and the swap never ran. Renaming one onto the other succeeds and does
        // nothing, and the caller would walk away believing the backup had been
        // consumed.
        val exe = file("Nexira-x86_64.AppImage", "OLD")
        val backup = dir.resolve("Nexira-x86_64.AppImage.backup")
        Files.createLink(backup, exe)

        applicator.restoreBackup(backup, exe)

        assertEquals("OLD", Files.readString(exe), "the rollback must leave a launcher at the path it was started from")
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `a download the user never installed is swept`() {
        file("Nexira-x86_64_0123456789abcdef.AppImage", "RUNNING")
        file("Nexira-x86_64_0123456789abcdef.AppImage.new", "NEVER INSTALLED")
        file("notes.txt", "keep me")

        assertEquals(
            listOf(dir.resolve("Nexira-x86_64_0123456789abcdef.AppImage.new")),
            applicator.leftoversIn(dir, "Nexira-x86_64_0123456789abcdef.AppImage"),
            "one staged image per version checked would otherwise pile up beside the launcher",
        )
    }

    @Test
    fun `a download staged for a binary without the AppImage extension is swept`() {
        file("nexira", "RUNNING")
        file("nexira.new", "NEVER INSTALLED")

        assertEquals(listOf(dir.resolve("nexira.new")), applicator.leftoversIn(dir, "nexira"))
    }

    @Test
    fun `a download an older build staged under the release name is swept`() {
        // Before updates went in place, the download was staged under the name of
        // the release it was fetching, not the running binary's.
        file("Nexira-x86_64.AppImage", "RUNNING")
        file("Nexira-2.4.6-x86_64.AppImage.new", "NEVER INSTALLED")

        assertEquals(
            listOf(dir.resolve("Nexira-2.4.6-x86_64.AppImage.new")),
            applicator.leftoversIn(dir, "Nexira-x86_64.AppImage"),
        )
    }
}
