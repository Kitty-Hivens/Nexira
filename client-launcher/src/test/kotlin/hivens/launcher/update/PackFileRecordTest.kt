package hivens.launcher.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackFileRecordTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() { dir = Files.createTempDirectory("pack-record-") }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun file(rel: String, text: String) {
        val p = dir.resolve(rel)
        p.parent?.createDirectories()
        p.writeText(text)
    }

    @Test
    fun `capture takes every file under the instance, nested included`() {
        file("mods/a.jar", "A")
        file("config/deep/b.toml", "B")

        val captured = PackFileRecord.capture(dir)

        assertEquals(setOf("mods/a.jar", "config/deep/b.toml"), captured.keys)
        assertEquals(1L, captured.getValue("mods/a.jar").size)
    }

    @Test
    fun `the record never records itself`() {
        file("mods/a.jar", "A")
        PackFileRecord.write(dir, PackFileRecord.capture(dir))

        // The second pass sees the file the first one wrote. If it took it in,
        // every install would leave a record whose own hash is already stale.
        val second = PackFileRecord.capture(dir)

        assertEquals(setOf("mods/a.jar"), second.keys)
    }

    @Test
    fun `a published hash is taken as given, the rest are computed`() {
        file("mods/a.jar", "A")
        file("config/b.toml", "B")

        val captured = PackFileRecord.capture(dir, publishedSha1 = mapOf("mods/a.jar" to "abc123"))

        assertEquals("abc123", captured.getValue("mods/a.jar").sha1, "the index's own hash is used as-is")
        // "B" -> sha1
        assertEquals("ae4f281df5a5d0ff3cad6371f76d5c29b6d953ec", captured.getValue("config/b.toml").sha1)
    }

    @Test
    fun `crc is carried for archive entries and absent for the rest`() {
        file("mods/a.jar", "A")
        file("config/b.toml", "B")

        val captured = PackFileRecord.capture(dir, archiveCrc32 = mapOf("config/b.toml" to 12345L))

        assertNull(captured.getValue("mods/a.jar").crc32, "a file fetched by URL has no archive entry")
        assertEquals(12345L, captured.getValue("config/b.toml").crc32)
    }

    @Test
    fun `write then read round-trips, spaces in a path included`() {
        file("mods/a.jar", "A")
        file("config/some name with spaces.toml", "B")

        val captured = PackFileRecord.capture(dir, archiveCrc32 = mapOf("config/some name with spaces.toml" to 7L))
        PackFileRecord.write(dir, captured)

        assertEquals(captured, PackFileRecord.read(dir))
    }

    @Test
    fun `the record is sorted, so it diffs`() {
        file("z.txt", "Z")
        file("a.txt", "A")
        PackFileRecord.write(dir, PackFileRecord.capture(dir))

        val paths = Files.readAllLines(dir.resolve(PackFileRecord.FILE_NAME)).map { it.substringAfterLast(" ") }

        assertEquals(listOf("a.txt", "z.txt"), paths)
    }

    @Test
    fun `an absent record reads as nothing known`() {
        assertEquals(emptyMap(), PackFileRecord.read(dir))
    }

    @Test
    fun `a damaged record drops the bad lines and keeps the rest`() {
        // Failing safe matters here: an update that believes it placed nothing
        // keeps its hands off everything, which is the harmless direction.
        dir.resolve(PackFileRecord.FILE_NAME).writeText(
            """
            aaa 1 2 - mods/good.jar
            this line is nonsense
            bbb notanumber 2 - mods/bad.jar

            ccc 3 4 99 config/also good.toml
            """.trimIndent(),
        )

        val read = PackFileRecord.read(dir)

        assertEquals(setOf("mods/good.jar", "config/also good.toml"), read.keys)
        assertEquals(99L, read.getValue("config/also good.toml").crc32)
    }

    @Test
    fun `an empty instance writes an empty record rather than nothing at all`() {
        PackFileRecord.write(dir, emptyMap())

        assertTrue(Files.exists(dir.resolve(PackFileRecord.FILE_NAME)))
        assertEquals(emptyMap(), PackFileRecord.read(dir))
    }
}
