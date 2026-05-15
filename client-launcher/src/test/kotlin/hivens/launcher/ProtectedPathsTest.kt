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
        // Defaults are NOT additive — user file fully replaces them. This is
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
