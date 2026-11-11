package hivens.launcher.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class DataDirMoverTest {

    private lateinit var workDir: Path
    private lateinit var confFile: Path
    private lateinit var source: Path
    private lateinit var target: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("nexira-mover-test-")
        confFile = workDir / "bootstrap.conf"
        source = workDir / "source"
        target = workDir / "target"
        Files.createDirectories(source)
        // Populate source with a couple of nested files to exercise the
        // recursive copy.
        Files.createDirectories(source / "subdir")
        Files.writeString(source / "credentials.json", """{"username":"test"}""")
        Files.writeString(source / "subdir" / "nested.txt", "nested content")
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    // ── schedule() ────────────────────────────────────────────────────────

    @Test
    fun `schedule writes pending source and target to conf`() {
        assertTrue(DataDirMover.schedule(source, target, confFile))

        val conf = BootstrapConf.read(confFile)
        assertEquals(source.toAbsolutePath().toString(), conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_PENDING_TARGET])
        // data-dir key NOT yet set -- only on successful apply.
        assertNull(conf[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    fun `schedule refuses same source and target (no-op)`() {
        assertFalse(DataDirMover.schedule(source, source, confFile))
        assertEquals(emptyMap(), BootstrapConf.read(confFile))
    }

    @Test
    fun `schedule refuses target inside source (would recurse during copy)`() {
        val nested = source / "subdir-as-new-data"
        assertFalse(DataDirMover.schedule(source, nested, confFile))
        assertEquals(emptyMap(), BootstrapConf.read(confFile))
    }

    // ── applyPending() ────────────────────────────────────────────────────

    @Test
    fun `applyPending copies tree, deletes source, commits new data-dir`() {
        DataDirMover.schedule(source, target, confFile)
        DataDirMover.applyPending(confFile)

        // Target has the files
        assertTrue(Files.exists(target / "credentials.json"))
        assertEquals("""{"username":"test"}""", Files.readString(target / "credentials.json"))
        assertTrue(Files.exists(target / "subdir" / "nested.txt"))
        assertEquals("nested content", Files.readString(target / "subdir" / "nested.txt"))

        // Source is gone
        assertFalse(Files.exists(source))

        // Conf reflects committed state -- pending cleared, data-dir set
        val conf = BootstrapConf.read(confFile)
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_DATA_DIR])
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertNull(conf[BootstrapConf.KEY_PENDING_TARGET])
    }

    @Test
    fun `applyPending when no pending is recorded -- no-op`() {
        // Empty conf -- should silently do nothing.
        DataDirMover.applyPending(confFile)
        // Source untouched.
        assertTrue(Files.exists(source / "credentials.json"))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `applyPending is idempotent -- source already moved, commits target as data-dir`() {
        // Simulate: previous apply succeeded for the copy but crashed
        // before clearing pending markers. On re-run, source is gone but
        // target has the data.
        DataDirMover.schedule(source, target, confFile)
        Files.createDirectories(target)
        Files.writeString(target / "credentials.json", """{"username":"test"}""")
        Files.walk(source).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }

        DataDirMover.applyPending(confFile)

        val conf = BootstrapConf.read(confFile)
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_DATA_DIR])
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
    }

    @Test
    fun `applyPending refuses to overwrite populated target -- clears pending`() {
        DataDirMover.schedule(source, target, confFile)
        // Pre-populate target with unrelated data.
        Files.createDirectories(target)
        Files.writeString(target / "stranger.txt", "this is not the launcher's data")

        DataDirMover.applyPending(confFile)

        // Source untouched
        assertTrue(Files.exists(source / "credentials.json"))
        // Target stranger file untouched
        assertEquals("this is not the launcher's data", Files.readString(target / "stranger.txt"))
        // Launcher files NOT copied into target (refused)
        assertFalse(Files.exists(target / "credentials.json"))
        // Pending cleared so we don't infinitely retry
        val conf = BootstrapConf.read(confFile)
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertNull(conf[BootstrapConf.KEY_DATA_DIR], "no commit on refused apply")
    }

    /**
     * On Windows this process holds files in the source open, so deleting it can fail
     * partway. Deleting before the commit left a half-deleted source as the data dir
     * and the complete copy unused, and the next start refused the populated target.
     */
    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `a source that will not delete still leaves the move committed, and goes at the next start`() {
        DataDirMover.schedule(source, target, confFile)
        val stuck = source / "subdir"
        Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            DataDirMover.applyPending(confFile)
            assumeTrue(Files.exists(stuck / "nested.txt"), "the directory could be emptied anyway (running as root)")

            val conf = BootstrapConf.read(confFile)
            assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_DATA_DIR])
            assertEquals(source.toAbsolutePath().toString(), conf[BootstrapConf.KEY_STALE_SOURCE])
            assertEquals("nested content", Files.readString(target / "subdir" / "nested.txt"))
        } finally {
            Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        DataDirMover.applyPending(confFile)

        assertFalse(Files.exists(source))
        assertNull(BootstrapConf.read(confFile)[BootstrapConf.KEY_STALE_SOURCE])
    }

    /** The guard against a populated target used to refuse the move's own unfinished copy for good. */
    @Test
    fun `an unfinished copy from an earlier start is copied again rather than refused`() {
        DataDirMover.schedule(source, target, confFile)
        Files.createDirectories(target)
        Files.writeString(target / DataDirMover.IN_PROGRESS_MARKER, source.toString())
        Files.writeString(target / "credentials.json", "half")

        DataDirMover.applyPending(confFile)

        assertEquals("""{"username":"test"}""", Files.readString(target / "credentials.json"))
        assertFalse(Files.exists(target / DataDirMover.IN_PROGRESS_MARKER))
        assertEquals(target.toAbsolutePath().toString(), BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `a copy that fails is removed and the move is tried again next start`() {
        DataDirMover.schedule(source, target, confFile)
        val unreadable = source / "subdir" / "nested.txt"
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"))
        try {
            DataDirMover.applyPending(confFile)
            assumeTrue(!Files.exists(target / "subdir" / "nested.txt"), "the file could be read anyway (running as root)")
            assertTrue(Files.exists(source / "credentials.json"))
            assertEquals(source.toAbsolutePath().toString(), BootstrapConf.read(confFile)[BootstrapConf.KEY_PENDING_SOURCE])
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-r--r--"))
        }

        DataDirMover.applyPending(confFile)

        assertEquals("nested content", Files.readString(target / "subdir" / "nested.txt"))
        assertEquals(target.toAbsolutePath().toString(), BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR])
    }

    /** `.lock` is held under a mandatory lock on Windows, and a read of it failed the whole copy. */
    @Test
    fun `the running process's own files are not carried into the new directory`() {
        Files.writeString(source / ".lock", "")
        Files.writeString(source / ".lock.pid", "123")
        DataDirMover.schedule(source, target, confFile)

        DataDirMover.applyPending(confFile)

        assertFalse(Files.exists(target / ".lock"))
        assertFalse(Files.exists(target / ".lock.pid"))
        assertEquals(target.toAbsolutePath().toString(), BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    fun `PlatformPaths picks up data-dir from bootstrap conf override`() {
        val custom = workDir / "custom-data-dir"
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to custom.toString()), confFile)

        val pp = PlatformPaths(
            osName = "Linux",
            home = workDir,
            env = { null }, // no NEXIRA_DATA_DIR
            bootstrapDataDir = { BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR]?.let { java.nio.file.Paths.get(it) } },
        )
        assertEquals(custom, pp.dataDir)
    }

    @Test
    fun `after applyPending, a fresh PlatformPaths reader sees the new data-dir`() {
        // Regression for the stale-paths bug in LauncherBootstrap.preBoot:
        // when a pending move applies, the rest of preBoot must re-resolve
        // PlatformPaths so Files.createDirectories, the bypass store,
        // SingleInstance.acquire, and Koin singletons all see the new dir
        // instead of recreating + wiring against the now-empty old path.
        // This test pins the underlying contract that the re-resolve relies
        // on: applyPending commits the new dir into BootstrapConf, and a
        // new PlatformPaths reader picks it up.
        DataDirMover.schedule(source, target, confFile)
        DataDirMover.applyPending(confFile)

        val freshAfterApply = PlatformPaths(
            osName = "Linux",
            home = workDir,
            env = { null },
            bootstrapDataDir = { BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR]?.let { java.nio.file.Paths.get(it) } },
        )
        assertEquals(
            target.toAbsolutePath().normalize(),
            freshAfterApply.dataDir.toAbsolutePath().normalize(),
        )
    }

    @Test
    fun `NEXIRA_DATA_DIR env wins over bootstrap conf override`() {
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to "/tmp/conf-side"), confFile)
        val envOverride = workDir / "env-side"

        val pp = PlatformPaths(
            osName = "Linux",
            home = workDir,
            env = { if (it == "NEXIRA_DATA_DIR") envOverride.toString() else null },
            bootstrapDataDir = { BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR]?.let { java.nio.file.Paths.get(it) } },
        )
        assertEquals(envOverride, pp.dataDir)
    }
}
