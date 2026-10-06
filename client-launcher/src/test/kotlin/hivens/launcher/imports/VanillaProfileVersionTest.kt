package hivens.launcher.imports

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a vanilla-launcher profile's version folder says it runs. */
@OptIn(ExperimentalPathApi::class)
class VanillaProfileVersionTest {

    private val root: Path = Files.createTempDirectory("dot-minecraft")
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun folder(id: String, body: String) {
        val dir = Files.createDirectories(root.resolve("versions").resolve(id))
        Files.writeString(dir.resolve("$id.json"), body)
    }

    @Test
    fun `a fabric folder names the game version it inherits and its loader`() {
        folder(
            "fabric-loader-0.16.0-1.21.1",
            """{"id":"fabric-loader-0.16.0-1.21.1","inheritsFrom":"1.21.1","libraries":[{"name":"net.fabricmc:fabric-loader:0.16.0"}]}""",
        )
        assertEquals(ProfileVersion("1.21.1", "fabric", "0.16.0"), vanillaProfileVersion(root, "fabric-loader-0.16.0-1.21.1", json))
    }

    @Test
    fun `the fabric loader over legacy fabric's mappings is legacy fabric`() {
        folder(
            "fabric-loader-0.19.5-1.8.9",
            """{"id":"fabric-loader-0.19.5-1.8.9","inheritsFrom":"1.8.9","libraries":[{"name":"net.legacyfabric:intermediary:1.8.9"},{"name":"net.fabricmc:fabric-loader:0.19.5"}]}""",
        )
        assertEquals(ProfileVersion("1.8.9", "legacy-fabric", "0.19.5"), vanillaProfileVersion(root, "fabric-loader-0.19.5-1.8.9", json))
    }

    @Test
    fun `an older legacy fabric install is recognised by its v2 intermediary`() {
        folder(
            "fabric-loader-0.14.0-1.8.9",
            """{"id":"fabric-loader-0.14.0-1.8.9","inheritsFrom":"1.8.9","libraries":[{"name":"net.legacyfabric.v2:intermediary:1.8.9"},{"name":"net.fabricmc:fabric-loader:0.14.0"}]}""",
        )
        assertEquals(ProfileVersion("1.8.9", "legacy-fabric", "0.14.0"), vanillaProfileVersion(root, "fabric-loader-0.14.0-1.8.9", json))
    }

    @Test
    fun `a forge folder gives the loader's own version, not the maven one`() {
        folder(
            "1.20.1-forge-47.2.0",
            """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.2.0:universal"}]}""",
        )
        assertEquals(ProfileVersion("1.20.1", "forge", "47.2.0"), vanillaProfileVersion(root, "1.20.1-forge-47.2.0", json))
    }

    @Test
    fun `a neoforge folder is read from its launch arguments`() {
        folder(
            "neoforge-21.1.1",
            """{"id":"neoforge-21.1.1","inheritsFrom":"1.21.1","arguments":{"game":["--fml.neoForgeVersion","21.1.1"]}}""",
        )
        assertEquals(ProfileVersion("1.21.1", "neoforge", "21.1.1"), vanillaProfileVersion(root, "neoforge-21.1.1", json))
    }

    @Test
    fun `a plain release needs no folder`() {
        assertEquals(ProfileVersion("1.21.1"), vanillaProfileVersion(root, "1.21.1", json))
    }

    @Test
    fun `a loader id with no folder is not taken for a game version`() {
        assertNull(vanillaProfileVersion(root, "fabric-loader-0.16.0-1.21.1", json))
    }

    @Test
    fun `a modded folder whose loader cannot be named is not read as vanilla`() {
        folder("mystery-1.21.1", """{"id":"mystery-1.21.1","inheritsFrom":"1.21.1","libraries":[{"name":"com.example:loader:1"}]}""")
        assertNull(vanillaProfileVersion(root, "mystery-1.21.1", json))
    }
}
