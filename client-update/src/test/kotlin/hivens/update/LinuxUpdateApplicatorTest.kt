package hivens.update

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
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

    // --- probation: the new version has to prove it started before the backup goes ---

    @Test
    fun `a confirmed start ends the probation and drops the backup`() {
        val exe = file("Nexira-x86_64.AppImage", "NEW")
        val backup = file("Nexira-x86_64.AppImage.backup", "OLD")
        val pending = file("Nexira-x86_64.AppImage.update-pending", "")

        applicator.confirmFor(exe)

        assertFalse(Files.exists(pending))
        assertFalse(Files.exists(backup), "a version that proved itself has nothing to roll back to")
    }

    @Test
    fun `a start with no update pending leaves an unrelated backup alone`() {
        val exe = file("Nexira-x86_64.AppImage", "CURRENT")
        val backup = file("Nexira-x86_64.AppImage.backup", "SOMETHING THE USER KEPT")

        applicator.confirmFor(exe)

        assertEquals("SOMETHING THE USER KEPT", Files.readString(backup))
    }

    /** The binaries the watchdog runs are stand-ins that write what happened to [log]. */
    private fun script(name: String, body: String): Path {
        val path = Files.writeString(dir.resolve(name), "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    private val log: Path get() = dir.resolve("log")

    private fun logged(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()

    private fun shellAvailable(): Boolean = Files.isExecutable(Path.of("/bin/sh"))

    /**
     * Runs the watchdog the way the hook starts it, with the launcher that
     * scheduled the update already gone unless [oldPid] says otherwise.
     */
    private fun watch(exe: Path, grace: Int = 30, oldPid: Long? = null) {
        val gone = oldPid ?: ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val process = ProcessBuilder("/bin/sh", "-c", LinuxUpdateApplicator.WATCHDOG_SCRIPT)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(
                    mapOf(
                        "EXE" to exe.toString(),
                        "BACKUP" to "$exe.backup",
                        "PENDING" to applicator.pendingFor(exe).toString(),
                        "INSTALLER" to dir.resolve("downloaded.AppImage").toString(),
                        "OLD_PID" to gone.toString(),
                        "GRACE" to grace.toString(),
                        "LOG" to log.toString(),
                    )
                )
                environment().remove(LinuxUpdateApplicator.ROLLED_BACK_ENV)
            }
            .start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the watchdog never finished")
    }

    @Test
    fun `a new version that dies before confirming is rolled back and the old one started`() {
        if (!shellAvailable()) return
        // The case the two-second check passed: up long enough to look alive, gone
        // before it ever got anywhere.
        val exe = script("Nexira-x86_64.AppImage", """echo new >> "${'$'}LOG"; exit 1""")
        script("Nexira-x86_64.AppImage.backup", """echo "old rolled_back=${'$'}NEXIRA_UPDATE_ROLLED_BACK" >> "${'$'}LOG"""")
        Files.createFile(applicator.pendingFor(exe))

        watch(exe)

        assertEquals(listOf("new", "old rolled_back=1"), logged())
        assertTrue(Files.readString(exe).contains("old rolled_back"), "the old version must be back on disk")
        assertFalse(Files.exists(applicator.pendingFor(exe)))
        assertFalse(Files.exists(dir.resolve("Nexira-x86_64.AppImage.backup")))
    }

    @Test
    fun `a new version that confirms is kept when it later exits`() {
        if (!shellAvailable()) return
        // What confirmStarted does, done by the stand-in itself.
        val exe = script(
            "Nexira-x86_64.AppImage",
            """echo new >> "${'$'}LOG"; rm -f "${'$'}PENDING" "${'$'}BACKUP"; exit 1""",
        )
        script("Nexira-x86_64.AppImage.backup", """echo old >> "${'$'}LOG"""")
        Files.createFile(applicator.pendingFor(exe))

        watch(exe)

        assertEquals(listOf("new"), logged())
        assertTrue(Files.readString(exe).contains("echo new"))
    }

    @Test
    fun `a new version that cannot confirm is kept once it outlasts the grace period`() {
        if (!shellAvailable()) return
        // An older release picked in the update manager knows nothing of the
        // confirmation and must not be rolled back for running normally.
        val exe = script("Nexira-x86_64.AppImage", """sleep 3; echo new >> "${'$'}LOG"""")
        val backup = script("Nexira-x86_64.AppImage.backup", """echo old >> "${'$'}LOG"""")
        Files.createFile(applicator.pendingFor(exe))

        watch(exe, grace = 1)

        assertEquals(listOf("new"), logged())
        assertFalse(Files.exists(backup), "a version kept by the timer has no backup left to restore")
    }

    @Test
    fun `with no update pending the launcher on disk is just started again`() {
        if (!shellAvailable()) return
        // The swap failed and the hook put the old version back.
        val exe = script("Nexira-x86_64.AppImage", """echo "old rolled_back=${'$'}NEXIRA_UPDATE_ROLLED_BACK" >> "${'$'}LOG"""")

        watch(exe)

        assertEquals(listOf("old rolled_back="), logged())
    }

    @Test
    fun `nothing starts until the launcher that scheduled the update has exited`() {
        if (!shellAvailable()) return
        // It holds the single-instance lock until it is gone, and a launcher
        // started while it is still there finds the lock taken and quits.
        val exe = script(
            "Nexira-x86_64.AppImage",
            """if kill -0 "${'$'}OLD_PID" 2>/dev/null; then echo "old still running" >> "${'$'}LOG"; else echo started >> "${'$'}LOG"; fi""",
        )
        val old = ProcessBuilder("sleep", "2").start()

        watch(exe, oldPid = old.pid())

        assertEquals(listOf("started"), logged())
    }

    @Test
    fun `the downloaded installer is removed once the old launcher is gone`() {
        if (!shellAvailable()) return
        val exe = script("Nexira-x86_64.AppImage", "true")
        val installer = file("downloaded.AppImage", "NEW")

        watch(exe)

        assertFalse(Files.exists(installer))
    }
}
