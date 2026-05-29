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
    fun `too few segments is rejected`() {
        assertFailsWith<IllegalArgumentException> { MavenCoord.parse("group:artifact") }
    }
}
