package hivens.launcher.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataDirMigrationTest {

    private lateinit var sandbox: Path
    private lateinit var paths: PlatformPaths

    @BeforeTest
    fun setUp() {
        sandbox = Files.createTempDirectory("aura-migration-test-")
        paths = PlatformPaths(
            osName = "Linux",
            home = sandbox,
            env = { if (it == "XDG_DATA_HOME") sandbox.resolve("data").toString() else null }
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun `no-op when legacy directory does not exist`() {
        DataDirMigration.run(paths)
        assertFalse(Files.exists(paths.dataDir), "target dir should not be created when legacy is absent")
    }

    @Test
    fun `copies files and writes marker`() {
        Files.createDirectories(paths.legacyDataDir.resolve("clients/Industrial"))
        Files.writeString(paths.legacyDataDir.resolve("settings.json"), "{}")
        Files.writeString(paths.legacyDataDir.resolve("clients/Industrial/icon.png"), "fake-icon")

        DataDirMigration.run(paths)

        assertEquals("{}", Files.readString(paths.dataDir.resolve("settings.json")))
        assertEquals(
            "fake-icon",
            Files.readString(paths.dataDir.resolve("clients/Industrial/icon.png"))
        )
        assertTrue(Files.exists(paths.legacyDataDir.resolve(".migrated")))
    }

    @Test
    fun `does not copy the migrated marker file itself`() {
        Files.createDirectories(paths.legacyDataDir)
        Files.writeString(paths.legacyDataDir.resolve("settings.json"), "{}")

        DataDirMigration.run(paths)

        assertFalse(
            Files.exists(paths.dataDir.resolve(".migrated")),
            "marker should stay in legacy dir, not be copied to target"
        )
    }

    @Test
    fun `skips when marker already present`() {
        Files.createDirectories(paths.legacyDataDir)
        Files.writeString(paths.legacyDataDir.resolve("settings.json"), "old")
        Files.writeString(paths.legacyDataDir.resolve(".migrated"), "previous run")

        DataDirMigration.run(paths)

        assertFalse(Files.exists(paths.dataDir.resolve("settings.json")))
    }

    @Test
    fun `skips and writes marker when target already populated`() {
        Files.createDirectories(paths.legacyDataDir)
        Files.writeString(paths.legacyDataDir.resolve("legacy.json"), "legacy")

        Files.createDirectories(paths.dataDir)
        Files.writeString(paths.dataDir.resolve("existing.json"), "existing")

        DataDirMigration.run(paths)

        assertFalse(
            Files.exists(paths.dataDir.resolve("legacy.json")),
            "legacy data must not overwrite already-populated target"
        )
        assertEquals("existing", Files.readString(paths.dataDir.resolve("existing.json")))
        assertTrue(Files.exists(paths.legacyDataDir.resolve(".migrated")))
    }

    @Test
    fun `target containing only housekeeping files still triggers migration`() {
        // Main.kt acquires the single-instance lock BEFORE running
        // migration, so .lock / .lock.pid / .show may already exist
        // in the (otherwise empty) target on a true first launch.
        // Migration must still see the directory as "no user data
        // here, proceed".
        Files.createDirectories(paths.legacyDataDir)
        Files.writeString(paths.legacyDataDir.resolve("legacy.json"), "legacy")

        Files.createDirectories(paths.dataDir)
        Files.writeString(paths.dataDir.resolve(".lock"), "")
        Files.writeString(paths.dataDir.resolve(".lock.pid"), "12345")
        Files.writeString(paths.dataDir.resolve(".show"), "")

        DataDirMigration.run(paths)

        assertEquals(
            "legacy",
            Files.readString(paths.dataDir.resolve("legacy.json")),
            "Migration must run when target contains only housekeeping (.lock / .lock.pid / .show)"
        )
        assertTrue(Files.exists(paths.legacyDataDir.resolve(".migrated")))
    }

    @Test
    fun `preserves directory tree depth`() {
        val deep = paths.legacyDataDir.resolve("clients/Industrial/assets/textures/blocks")
        Files.createDirectories(deep)
        Files.writeString(deep.resolve("stone.png"), "stonebytes")

        DataDirMigration.run(paths)

        val migrated = paths.dataDir.resolve("clients/Industrial/assets/textures/blocks/stone.png")
        assertContentEquals("stonebytes".toByteArray(), Files.readAllBytes(migrated))
    }
}
