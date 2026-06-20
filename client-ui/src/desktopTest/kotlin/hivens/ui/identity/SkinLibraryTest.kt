package hivens.ui.identity

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinLibraryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var root: Path
    private lateinit var lib: SkinLibrary

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-skinlib-")
        lib = SkinLibrary(root / "skins", json)
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun `add stores the png and lists the entry`() {
        val e = lib.add(png, "Cool", slim = true, now = 100)
        assertEquals("Cool", e.name)
        assertTrue(e.slim)
        assertContentEquals(png, lib.bytes(e.id))
        assertEquals(listOf(e.id), lib.list().map { it.id })
    }

    @Test
    fun `list is newest first`() {
        val a = lib.add(png, "A", false, now = 1)
        val b = lib.add(png, "B", false, now = 2)
        assertEquals(listOf(b.id, a.id), lib.list().map { it.id })
    }

    @Test
    fun `delete removes the entry and the file`() {
        val e = lib.add(png, "X", false, now = 1)
        lib.delete(e.id)
        assertTrue(lib.list().isEmpty())
        assertNull(lib.bytes(e.id))
        assertFalse(Files.exists(lib.file(e.id)))
    }

    @Test
    fun `rename updates the name -- blank keeps the old`() {
        val e = lib.add(png, "Old", false, now = 1)
        lib.rename(e.id, "New")
        assertEquals("New", lib.list().single().name)
        lib.rename(e.id, "  ")
        assertEquals("New", lib.list().single().name)
    }

    @Test
    fun `blank name falls back`() {
        assertEquals("skin", lib.add(png, "  ", false, now = 1).name)
    }

    @Test
    fun `empty library lists nothing`() {
        assertTrue(lib.list().isEmpty())
    }

    @Test
    fun `corrupt index is treated as empty`() {
        Files.createDirectories(root / "skins")
        Files.writeString((root / "skins").resolve("library.json"), "not json {{{")
        assertTrue(lib.list().isEmpty())
    }

    @Test
    fun `bytes is null when the file is gone behind a live entry`() {
        val e = lib.add(png, "X", false, now = 1)
        Files.delete(lib.file(e.id))
        assertNull(lib.bytes(e.id))
        assertEquals(listOf(e.id), lib.list().map { it.id }, "metadata survives a missing file")
    }
}
