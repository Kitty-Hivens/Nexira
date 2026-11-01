package hivens.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MavenCoordTest {

    @Test
    fun `plain coordinate maps to maven repo path`() {
        val c = MavenCoord.parse("com.mojang:patchy:1.3.9")
        assertEquals("com.mojang", c.group)
        assertEquals("patchy", c.artifact)
        assertEquals("1.3.9", c.version)
        assertNull(c.classifier)
        assertEquals("jar", c.extension)
        assertEquals("com/mojang/patchy/1.3.9/patchy-1.3.9.jar", c.relativePath)
        assertEquals("com.mojang:patchy", c.groupArtifact)
    }

    @Test
    fun `classifier lands in the filename`() {
        val c = MavenCoord.parse("org.lwjgl:lwjgl:3.3.3:natives-linux")
        assertEquals("natives-linux", c.classifier)
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar", c.relativePath)
    }

    @Test
    fun `extension suffix overrides jar (forge mcp_config zip)`() {
        val c = MavenCoord.parse("de.oceanlabs.mcp:mcp_config:1.12.2@zip")
        assertEquals("zip", c.extension)
        assertNull(c.classifier)
        assertEquals("de/oceanlabs/mcp/mcp_config/1.12.2/mcp_config-1.12.2.zip", c.relativePath)
    }

    @Test
    fun `classifier and extension together`() {
        val c = MavenCoord.parse("net.minecraftforge:forge:1.12.2-14.23.5.2860:universal@jar")
        assertEquals("universal", c.classifier)
        assertEquals("jar", c.extension)
        assertEquals(
            "net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-universal.jar",
            c.relativePath,
        )
    }

    @Test
    fun `groupArtifact is version-independent (the dedup key)`() {
        assertEquals(
            MavenCoord.parse("org.ow2.asm:asm:5.0.3").groupArtifact,
            MavenCoord.parse("org.ow2.asm:asm:9.9").groupArtifact,
        )
    }

    @Test
    fun `nativeClassifier reads a maven classifier (Cleanroom form)`() {
        assertEquals("natives-linux", MavenCoord.parse("org.lwjgl:lwjgl:3.4.1:natives-linux").nativeClassifier)
        assertEquals("natives-windows-arm64", MavenCoord.parse("org.lwjgl:lwjgl-glfw:3.4.1:natives-windows-arm64").nativeClassifier)
    }

    @Test
    fun `nativeClassifier reads a name-encoded native (lwjgl3ify form)`() {
        assertEquals("natives-linux", MavenCoord.parse("org.lwjgl:lwjgl-opengl-natives-linux:3.4.2").nativeClassifier)
        assertEquals("natives-macos-arm64", MavenCoord.parse("org.lwjgl:lwjgl-stb-natives-macos-arm64:3.4.2").nativeClassifier)
    }

    @Test
    fun `nativeClassifier is null for a non-native jar in either form`() {
        assertNull(MavenCoord.parse("org.lwjgl:lwjgl-opengl:3.4.2").nativeClassifier)
        assertNull(MavenCoord.parse("com.google.guava:guava:33.6.0-jre").nativeClassifier)
        // a non-natives classifier (e.g. Forge universal) is not a native either.
        assertNull(MavenCoord.parse("net.minecraftforge:forge:1.12.2-14.23.5.2860:universal").nativeClassifier)
    }

    @Test
    fun `too few segments is rejected`() {
        assertFailsWith<IllegalArgumentException> { MavenCoord.parse("group:artifact") }
    }
}
