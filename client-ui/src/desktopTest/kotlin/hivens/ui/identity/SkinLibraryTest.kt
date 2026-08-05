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
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun `a truncated index does not wipe the library on the next mutation`() {
        val a = lib.add(png, "Alpha", slim = false, now = 100)
        val b = lib.add(png, "Beta", slim = false, now = 200)
        assertEquals(2, lib.list().size)

        // What an interrupted write leaves behind. Read as "empty", the very next
        // read-modify-write would persist that emptiness over the real index and
        // strand both pngs on disk forever.
        Files.writeString(root / "skins" / "library.json", "{\"skins\":[{\"id\":\"a")

        val recovered = lib.list()
        assertEquals(2, recovered.size, "both pngs are still on disk and must stay reachable")
        assertEquals(setOf(a.id, b.id), recovered.map { it.id }.toSet())

        // The unreadable file is kept rather than overwritten, so it can be looked at.
        assertTrue(Files.exists(root / "skins" / "library.json.corrupt"))

        // And a mutation after the recovery does not lose the other entry.
        lib.delete(a.id)
        assertEquals(listOf(b.id), lib.list().map { it.id })
    }

    @Test
    fun `a missing index is simply an empty library`() {
        assertEquals(emptyList(), lib.list())
        assertFalse(Files.exists(root / "skins" / "library.json.corrupt"), "nothing to quarantine")
    }

    @Test
    fun `concurrent imports do not drop an entry`() {
        // The wardrobe re-imports the server skin on every open, which can overlap
        // a user import. Two read-modify-writes racing would lose one.
        val threads = (1..8).map { i ->
            Thread { lib.add(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, i.toByte()), "s$i", false, i.toLong()) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, lib.list().size, "an entry was lost to a concurrent write")
    }

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
    fun `markApplied records the timestamp and activeId tracks the latest`() {
        val a = lib.add(png, "A", false, now = 1)
        val b = lib.add(png, "B", false, now = 2)
        assertNull(lib.activeId())
        lib.markApplied(a.id, now = 10)
        assertEquals(a.id, lib.activeId())
        assertEquals(10L, lib.list().first { it.id == a.id }.lastAppliedAt)
        lib.markApplied(b.id, now = 20)
        assertEquals(b.id, lib.activeId(), "newest application wins")
    }

    @Test
    fun `kind separates skins from capes and activeId is per-kind`() {
        val skin = lib.add(png, "S", false, now = 1, kind = SkinLibrary.Kind.Skin)
        val cape = lib.add(png, "", false, now = 2, kind = SkinLibrary.Kind.Cape)
        assertEquals("cape", cape.name, "blank cape name falls back to its kind")
        assertEquals(listOf(skin.id), lib.list(SkinLibrary.Kind.Skin).map { it.id })
        assertEquals(listOf(cape.id), lib.list(SkinLibrary.Kind.Cape).map { it.id })
        assertEquals(2, lib.list().size, "no-kind list returns both")
        lib.markApplied(skin.id, now = 10)
        lib.markApplied(cape.id, now = 20)
        assertEquals(skin.id, lib.activeId(SkinLibrary.Kind.Skin))
        assertEquals(cape.id, lib.activeId(SkinLibrary.Kind.Cape))
    }

    @Test
    fun `addUnique dedups on matching sha, adds on a new one, and always adds when sha is null`() {
        val first = lib.addUnique(png, "current", false, now = 1, sha = "aaa")
        // Same sha -> the existing entry, no second file.
        val again = lib.addUnique(png, "current-again", false, now = 2, sha = "aaa")
        assertEquals(first.id, again.id)
        assertEquals(1, lib.list().size)
        assertEquals("current", lib.list().single().name, "the existing entry is untouched")
        // Different sha -> a new entry.
        val other = lib.addUnique(png, "other", false, now = 3, sha = "bbb")
        assertEquals(2, lib.list().size)
        // Null sha (undecodable) skips the dedup and always adds.
        lib.addUnique(png, "blind", false, now = 4, sha = null)
        lib.addUnique(png, "blind", false, now = 5, sha = null)
        assertEquals(4, lib.list().size)
        assertTrue(other.id in lib.list().map { it.id })
    }

    @Test
    fun `addUnique dedup is per-kind -- a cape does not match a skin sha`() {
        val skin = lib.addUnique(png, "s", false, now = 1, sha = "dup", kind = SkinLibrary.Kind.Skin)
        val cape = lib.addUnique(png, "c", false, now = 2, sha = "dup", kind = SkinLibrary.Kind.Cape)
        assertTrue(skin.id != cape.id, "same sha across kinds still makes two entries")
        assertEquals(2, lib.list().size)
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
