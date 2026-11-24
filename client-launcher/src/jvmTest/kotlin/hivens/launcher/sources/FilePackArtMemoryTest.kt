package hivens.launcher.sources

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FilePackArtMemoryTest {

    private val dir: Path = createTempDirectory("pack-art")
    private val file: Path = dir.resolve("cache").resolve("pack-art.json")
    private val memory = FilePackArtMemory(file, Json)

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `what is saved is loaded, a field with no picture included`() {
        val art = mapOf("modrinth:a" to PackArt("https://cdn/a.png", null), "mirror:b" to PackArt.NONE)
        memory.save(art)
        assertEquals(art, FilePackArtMemory(file, Json).load())
    }

    @Test
    fun `no file yet is nothing remembered`() {
        assertEquals(emptyMap(), memory.load())
    }

    @Test
    fun `a file that cannot be read is nothing remembered, and the next save replaces it`() {
        Files.createDirectories(file.parent)
        file.writeText("{ not json")
        assertEquals(emptyMap(), memory.load())

        memory.save(mapOf("mirror:b" to PackArt("https://cdn/b.png", null)))
        assertEquals("https://cdn/b.png", memory.load()["mirror:b"]?.iconUrl)
    }
}
