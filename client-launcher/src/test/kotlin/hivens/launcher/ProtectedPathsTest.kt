package hivens.launcher

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedPathsTest {

    private lateinit var dataDir: Path
    private lateinit var configFile: Path

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("protected-paths-test")
        dataDir.toFile().deleteOnExit()
        configFile = dataDir.resolve("protected-paths.json")
    }

    @AfterTest
    fun tearDown() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `first run writes default config and uses defaults`() {
        val pp = ProtectedPaths(configFile, json)
        // Trigger lazy load
        assertTrue(pp.isProtected("options.txt"))

        assertTrue(Files.exists(configFile), "default config should be written on first read")
        val written = Files.readString(configFile)
        assertContains(written, "options.txt")
        assertContains(written, "xaerominimap")
    }

    @Test
    fun `endsWith pattern matches case-insensitively after path normalization`() {
        val pp = ProtectedPaths(configFile, json)
        assertTrue(pp.isProtected("options.txt"))
        assertTrue(pp.isProtected("OPTIONS.TXT"))
        assertTrue(pp.isProtected("Industrial/options.txt"))
        assertTrue(pp.isProtected("Industrial\\options.txt"), "backslashes must be normalized to /")
        assertTrue(pp.isProtected("servers.dat"))
    }

    @Test
    fun `contains pattern matches mod directory anywhere in path`() {
        val pp = ProtectedPaths(configFile, json)
        assertTrue(pp.isProtected("config/xaerominimap/waypoints.dat"))
        assertTrue(pp.isProtected("config/voxelmap/settings.txt"))
        assertTrue(pp.isProtected("Industrial/config/jei/recipe-history.json"))
    }

    @Test
    fun `non-protected paths return false`() {
        val pp = ProtectedPaths(configFile, json)
        assertFalse(pp.isProtected("mods/somemod-1.0.jar"))
        assertFalse(pp.isProtected("config/forge.cfg"))
        assertFalse(pp.isProtected("libraries/net/foo/bar.jar"))
    }

    @Test
    fun `a mod's own jar is not protected by the entry that guards its config`() {
        val pp = ProtectedPaths(configFile, json)
        // Every default `contains` entry is a substring of its own mod's filename.
        // Protecting the jar means it is never updated, never retired on a pack
        // version bump, and never repaired when the archive is corrupt -- while
        // the config directory the entry exists for must stay protected.
        assertFalse(pp.isProtected("mods/jei-1.20.1-forge-15.2.0.27.jar"))
        assertFalse(pp.isProtected("mods/journeymap-1.20.1-5.9.18-forge.jar"))
        assertFalse(pp.isProtected("mods/voxelmap-1.20.1.jar"))
        assertFalse(pp.isProtected("mods/xaerominimap_24.2.0_Forge_1.20.1.jar"))
        assertFalse(pp.isProtected("Industrial/mods/JourneyMap-1.12.2.jar"))

        assertTrue(pp.isProtected("config/jei/recipe-history.json"))
        assertTrue(pp.isProtected("journeymap/data/waypoints.json"))
        assertTrue(pp.isProtected("mods/VoxelMods/voxelmap/cache.dat"))
        assertTrue(pp.isProtected("XaeroMinimap/Multiplayer_srv/dim0.png"))
    }

    @Test
    fun `a contains entry never matches a bare filename at the client root`() {
        val pp = ProtectedPaths(configFile, json)
        // No directory portion at all -- only `endsWith` may claim these.
        assertFalse(pp.isProtected("jei.jar"))
        assertTrue(pp.isProtected("options.txt"))
    }

    @Test
    fun `user-extended config is honoured`() {
        Files.writeString(
            configFile,
            """
            {
              "endsWith": ["my-special-config.txt"],
              "contains": ["mymod"]
            }
            """.trimIndent()
        )

        val pp = ProtectedPaths(configFile, json)
        assertTrue(pp.isProtected("config/my-special-config.txt"))
        assertTrue(pp.isProtected("mods/mymod/data.bin"))
        // Defaults are NOT additive -- user file fully replaces them. This is
        // intentional: keeps the file shape predictable for users editing it
        // by hand. If you want defaults+yours, copy the defaults in.
        assertFalse(pp.isProtected("options.txt"))
    }

    @Test
    fun `malformed json falls back to defaults without overwriting user file`() {
        Files.writeString(configFile, "{ this is not valid json }")
        val pp = ProtectedPaths(configFile, json)

        // Defaults still apply
        assertTrue(pp.isProtected("options.txt"))
        // User's broken file is left intact (we don't auto-overwrite their data)
        assertContains(Files.readString(configFile), "this is not valid json")
    }
}
