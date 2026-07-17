package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileManifestBuildTest {

    @Test
    fun `empty entries yield empty manifest`() {
        val m = fileManifestOf(emptyMap())
        assertTrue(m.files.isEmpty() && m.directories.isEmpty())
    }

    @Test
    fun `root-level entry stays a root file`() {
        val m = fileManifestOf(mapOf("options.txt" to FileData(sha1 = "a", size = 10L)))
        assertEquals(setOf("options.txt"), m.files.keys)
        assertTrue(m.directories.isEmpty())
    }

    @Test
    fun `slashed path nests into directories`() {
        val m = fileManifestOf(mapOf("mods/quark.jar" to FileData(sha1 = "h", size = 5L)))
        assertTrue(m.files.isEmpty())
        val mods = m.directories["mods"]!!
        assertEquals("h", mods.files["quark.jar"]!!.sha1)
    }

    @Test
    fun `round-trips flatten for a mixed tree`() {
        val flat = mapOf(
            "options.txt" to FileData(sha1 = "o", size = 1L),
            "mods/a.jar" to FileData(sha1 = "a", size = 2L),
            "config/mod/recipes.cfg" to FileData(sha1 = "r", size = 3L),
        )
        assertEquals(flat, fileManifestOf(flat).flatten())
    }

    @Test
    fun `empty path segments are dropped`() {
        // A leading or doubled slash must not create a nameless directory node.
        val m = fileManifestOf(mapOf("/mods//a.jar" to FileData(sha1 = "a")))
        assertEquals(mapOf("mods/a.jar" to FileData(sha1 = "a")), m.flatten())
    }
}
