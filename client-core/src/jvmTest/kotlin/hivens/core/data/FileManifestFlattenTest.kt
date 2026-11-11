package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileManifestFlattenTest {

    @Test
    fun `empty manifest yields empty map`() {
        assertTrue(FileManifest().flatten().isEmpty())
    }

    @Test
    fun `root-level files keep their bare key`() {
        val manifest = FileManifest(
            files = mapOf("extra.zip" to FileData(md5 = "abc", size = 100L)),
        )
        val flat = manifest.flatten()
        assertEquals(1, flat.size)
        assertTrue(flat.containsKey("extra.zip"))
    }

    @Test
    fun `directory keys join with slashes`() {
        val manifest = FileManifest(
            directories = mapOf(
                "mods" to FileManifest(
                    files = mapOf(
                        "industrialcraft.jar" to FileData(md5 = "111", size = 1000L),
                        "buildcraft.jar" to FileData(md5 = "222", size = 2000L),
                    ),
                ),
            ),
        )
        val flat = manifest.flatten()
        assertEquals(2, flat.size)
        assertTrue(flat.containsKey("mods/industrialcraft.jar"))
        assertTrue(flat.containsKey("mods/buildcraft.jar"))
    }

    @Test
    fun `recurses into deeply nested directory trees`() {
        val manifest = FileManifest(
            directories = mapOf(
                "config" to FileManifest(
                    directories = mapOf(
                        "industrialcraft" to FileManifest(
                            files = mapOf("recipes.cfg" to FileData(md5 = "x", size = 10L)),
                        ),
                    ),
                ),
            ),
        )
        val flat = manifest.flatten()
        assertEquals(1, flat.size)
        assertTrue(flat.containsKey("config/industrialcraft/recipes.cfg"))
    }

    @Test
    fun `insertion order is preserved`() {
        val manifest = FileManifest(
            files = mapOf("root.txt" to FileData()),
            directories = mapOf(
                "libraries" to FileManifest(
                    files = mapOf("authlib.jar" to FileData()),
                ),
            ),
        )
        // Files emit before the directory walk recurses; a caller taking the
        // first match over the flattened entries gets a deterministic pick.
        assertEquals(listOf("root.txt", "libraries/authlib.jar"), manifest.flatten().keys.toList())
    }
}
