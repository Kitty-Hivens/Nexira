package hivens.launcher.instance

import hivens.launcher.update.PackFileRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackPlacedContentTest {

    private val dir: Path = Files.createTempDirectory("placed")

    @AfterTest fun cleanUp() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun place(rel: String) {
        val file = dir.resolve(rel)
        file.parent.createDirectories()
        file.writeText(rel)
    }

    @Test fun `an instance with no record answers unknown rather than empty`() {
        assertNull(
            PackPlacedContent.paths(dir),
            "an absent record must not read as 'the pack placed nothing' -- that hands the pack's own files to the player",
        )
    }

    @Test fun `reads back what the installer recorded`() {
        place("mods/sodium.jar")
        place("config/sodium-options.json")
        PackFileRecord.write(dir, PackFileRecord.captureAll(dir))

        val placed = PackPlacedContent.paths(dir)

        assertEquals(setOf("mods/sodium.jar", "config/sodium-options.json"), placed)
    }

    @Test fun `a file added after the install is not the pack's`() {
        place("mods/sodium.jar")
        PackFileRecord.write(dir, PackFileRecord.captureAll(dir))
        place("mods/journeymap.jar")

        val placed = PackPlacedContent.paths(dir).orEmpty()

        assertTrue("mods/sodium.jar" in placed)
        assertTrue(
            "mods/journeymap.jar" !in placed,
            "the record is what the pack put here, not what is here now",
        )
    }

    @Test fun `an empty record is an answer, and it is not null`() {
        PackFileRecord.write(dir, emptyMap())

        assertEquals(emptySet(), PackPlacedContent.paths(dir))
    }
}
