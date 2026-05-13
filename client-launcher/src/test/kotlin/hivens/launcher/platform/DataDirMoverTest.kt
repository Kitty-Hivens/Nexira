package hivens.launcher.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataDirMoverTest {

    private lateinit var workDir: Path
    private lateinit var confFile: Path
    private lateinit var source: Path
    private lateinit var target: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-mover-test-")
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
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── schedule() ────────────────────────────────────────────────────────

    @Test
    fun `schedule writes pending source and target to conf`() {
        assertTrue(DataDirMover.schedule(source, target, confFile))

        val conf = BootstrapConf.read(confFile)
        assertEquals(source.toAbsolutePath().toString(), conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_PENDING_TARGET])
        // data-dir key NOT yet set — only on successful apply.
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

        // Conf reflects committed state — pending cleared, data-dir set
        val conf = BootstrapConf.read(confFile)
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_DATA_DIR])
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertNull(conf[BootstrapConf.KEY_PENDING_TARGET])
    }

    @Test
    fun `applyPending when no pending is recorded — no-op`() {
        // Empty conf — should silently do nothing.
        DataDirMover.applyPending(confFile)
        // Source untouched.
        assertTrue(Files.exists(source / "credentials.json"))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `applyPending is idempotent — source already moved, commits target as data-dir`() {
        // Simulate: previous apply succeeded for the copy but crashed
        // before clearing pending markers. On re-run, source is gone but
        // target has the data.
        DataDirMover.schedule(source, target, confFile)
        Files.createDirectories(target)
        Files.writeString(target / "credentials.json", """{"username":"test"}""")
        Files.walk(source).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }

        DataDirMover.applyPending(confFile)

        val conf = BootstrapConf.read(confFile)
        assertEquals(target.toAbsolutePath().toString(), conf[BootstrapConf.KEY_DATA_DIR])
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
    }

    @Test
    fun `applyPending refuses to overwrite populated target — clears pending`() {
        DataDirMover.schedule(source, target, confFile)
        // Pre-populate target with unrelated data.
        Files.createDirectories(target)
        Files.writeString(target / "stranger.txt", "this is not Aura's data")

        DataDirMover.applyPending(confFile)

        // Source untouched
        assertTrue(Files.exists(source / "credentials.json"))
        // Target stranger file untouched
        assertEquals("this is not Aura's data", Files.readString(target / "stranger.txt"))
        // Aura files NOT copied into target (refused)
        assertFalse(Files.exists(target / "credentials.json"))
        // Pending cleared so we don't infinitely retry
        val conf = BootstrapConf.read(confFile)
        assertNull(conf[BootstrapConf.KEY_PENDING_SOURCE])
        assertNull(conf[BootstrapConf.KEY_DATA_DIR], "no commit on refused apply")
    }

    @Test
    fun `PlatformPaths picks up data-dir from bootstrap conf override`() {
        val custom = workDir / "custom-data-dir"
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to custom.toString()), confFile)

        val pp = PlatformPaths(
            osName = "Linux",
            home = workDir,
            env = { null }, // no AURA_DATA_DIR
            bootstrapDataDir = { BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR]?.let { java.nio.file.Paths.get(it) } },
        )
        assertEquals(custom, pp.dataDir)
    }

    @Test
    fun `AURA_DATA_DIR env wins over bootstrap conf override`() {
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to "/tmp/conf-side"), confFile)
        val envOverride = workDir / "env-side"

        val pp = PlatformPaths(
            osName = "Linux",
            home = workDir,
            env = { if (it == "AURA_DATA_DIR") envOverride.toString() else null },
            bootstrapDataDir = { BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR]?.let { java.nio.file.Paths.get(it) } },
        )
        assertEquals(envOverride, pp.dataDir)
    }
}
