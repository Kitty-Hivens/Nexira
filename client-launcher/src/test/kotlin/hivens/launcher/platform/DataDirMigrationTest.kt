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
        sandbox = Files.createTempDirectory("nexira-migration-test-")
        paths = PlatformPaths(
            osName = "Linux",
            home = sandbox,
            env = { if (it == "XDG_DATA_HOME") sandbox.resolve("data").toString() else null }
        )
    }

    /** Aura-era default legacy (first priority) -- `$XDG_DATA_HOME/aura-launcher` here. */
    private val auraEraLegacy get() = paths.legacyDataDirs[0]

    /** Pre-2.3 legacy (second priority) -- `~/.aura` here. */
    private val pre23Legacy get() = paths.legacyDataDirs[1]

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
        Files.createDirectories(auraEraLegacy.resolve("clients/Industrial"))
        Files.writeString(auraEraLegacy.resolve("settings.json"), "{}")
        Files.writeString(auraEraLegacy.resolve("clients/Industrial/icon.png"), "fake-icon")

        DataDirMigration.run(paths)

        assertEquals("{}", Files.readString(paths.dataDir.resolve("settings.json")))
        assertEquals(
            "fake-icon",
            Files.readString(paths.dataDir.resolve("clients/Industrial/icon.png"))
        )
        assertTrue(Files.exists(auraEraLegacy.resolve(".migrated")))
    }

    @Test
    fun `does not copy the migrated marker file itself`() {
        Files.createDirectories(auraEraLegacy)
        Files.writeString(auraEraLegacy.resolve("settings.json"), "{}")

        DataDirMigration.run(paths)

        assertFalse(
            Files.exists(paths.dataDir.resolve(".migrated")),
            "marker should stay in legacy dir, not be copied to target"
        )
    }

    @Test
    fun `skips when marker already present`() {
        Files.createDirectories(auraEraLegacy)
        Files.writeString(auraEraLegacy.resolve("settings.json"), "old")
        Files.writeString(auraEraLegacy.resolve(".migrated"), "previous run")

        DataDirMigration.run(paths)

        assertFalse(Files.exists(paths.dataDir.resolve("settings.json")))
    }

    @Test
    fun `skips and writes marker when target already populated`() {
        Files.createDirectories(auraEraLegacy)
        Files.writeString(auraEraLegacy.resolve("legacy.json"), "legacy")

        Files.createDirectories(paths.dataDir)
        Files.writeString(paths.dataDir.resolve("existing.json"), "existing")

        DataDirMigration.run(paths)

        assertFalse(
            Files.exists(paths.dataDir.resolve("legacy.json")),
            "legacy data must not overwrite already-populated target"
        )
        assertEquals("existing", Files.readString(paths.dataDir.resolve("existing.json")))
        assertTrue(Files.exists(auraEraLegacy.resolve(".migrated")))
    }

    @Test
    fun `target containing only housekeeping files still triggers migration`() {
        // Main.kt acquires the single-instance lock BEFORE running
        // migration, so .lock / .lock.pid / .show may already exist
        // in the (otherwise empty) target on a true first launch.
        // Migration must still see the directory as "no user data
        // here, proceed".
        Files.createDirectories(auraEraLegacy)
        Files.writeString(auraEraLegacy.resolve("legacy.json"), "legacy")

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
        assertTrue(Files.exists(auraEraLegacy.resolve(".migrated")))
    }

    @Test
    fun `preserves directory tree depth`() {
        val deep = auraEraLegacy.resolve("clients/Industrial/assets/textures/blocks")
        Files.createDirectories(deep)
        Files.writeString(deep.resolve("stone.png"), "stonebytes")

        DataDirMigration.run(paths)

        val migrated = paths.dataDir.resolve("clients/Industrial/assets/textures/blocks/stone.png")
        assertContentEquals("stonebytes".toByteArray(), Files.readAllBytes(migrated))
    }

    @Test
    fun `Aura-era legacy wins over pre-2_3 ~_aura when both populated`() {
        Files.createDirectories(auraEraLegacy)
        Files.writeString(auraEraLegacy.resolve("from-aura-era.json"), "modern-aura")
        Files.createDirectories(pre23Legacy)
        Files.writeString(pre23Legacy.resolve("from-pre23.json"), "old-aura")

        DataDirMigration.run(paths)

        assertEquals("modern-aura", Files.readString(paths.dataDir.resolve("from-aura-era.json")))
        assertFalse(
            Files.exists(paths.dataDir.resolve("from-pre23.json")),
            "Pre-2.3 legacy must be ignored when the more recent Aura-era legacy exists",
        )
        assertTrue(Files.exists(auraEraLegacy.resolve(".migrated")))
        assertFalse(Files.exists(pre23Legacy.resolve(".migrated")), "Untouched legacy stays unmarked")
    }

    @Test
    fun `falls back to pre-2_3 ~_aura when Aura-era legacy is absent`() {
        Files.createDirectories(pre23Legacy)
        Files.writeString(pre23Legacy.resolve("ancient.json"), "ancient")

        DataDirMigration.run(paths)

        assertEquals("ancient", Files.readString(paths.dataDir.resolve("ancient.json")))
        assertTrue(Files.exists(pre23Legacy.resolve(".migrated")))
    }
}
