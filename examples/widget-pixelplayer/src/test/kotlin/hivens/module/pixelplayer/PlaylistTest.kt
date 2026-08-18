package hivens.module.pixelplayer

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistTest {

    private val root = createTempDirectory("pixelplayer")

    private fun file(rel: String) = root.resolve(rel).also {
        it.parent.createDirectories()
        it.writeText("x")
    }

    @Test
    fun `only the wanted extensions are taken`() {
        // The case this exists for: a song library where every track ships in
        // two formats and only one of them is worth queueing.
        file("a/audio.mp3"); file("a/audio.ogg"); file("a/thumb.jpg")
        val found = Playlist.scan(root, recursive = true, extensions = setOf("mp3"))
        assertEquals(listOf("audio.mp3"), found.map { it.name })
    }

    @Test
    fun `without recursion only the top level is seen`() {
        file("top.mp3"); file("nested/deep.mp3")
        assertEquals(listOf("top.mp3"), Playlist.scan(root, recursive = false, extensions = setOf("mp3")).map { it.name })
    }

    @Test
    fun `with recursion the whole tree is seen`() {
        file("top.mp3"); file("nested/deep.mp3"); file("nested/deeper/deepest.mp3")
        assertEquals(3, Playlist.scan(root, recursive = true, extensions = setOf("mp3")).size)
    }

    @Test
    fun `order is stable across scans`() {
        file("b/two.mp3"); file("a/one.mp3"); file("c/three.mp3")
        val first = Playlist.scan(root, true, setOf("mp3"))
        val second = Playlist.scan(root, true, setOf("mp3"))
        assertEquals(first, second)
        assertEquals(listOf("one.mp3", "two.mp3", "three.mp3"), first.map { it.name })
    }

    @Test
    fun `a missing folder is empty rather than an error`() {
        // The prop is a text field; it will be wrong or unset most of its life.
        assertTrue(Playlist.scan(root.resolve("nope"), true, setOf("mp3")).isEmpty())
    }

    @Test
    fun `no extensions means nothing, not everything`() {
        file("a.mp3")
        assertTrue(Playlist.scan(root, true, emptySet()).isEmpty())
    }

    @Test
    fun `extensions are parsed the way a person types them`() {
        assertEquals(setOf("mp3", "flac", "wav"), Playlist.parseExtensions("mp3, .FLAC ,, wav"))
        assertEquals(emptySet(), Playlist.parseExtensions("   "))
    }

    @Test
    fun `matching ignores case`() {
        file("LOUD.MP3")
        assertEquals(1, Playlist.scan(root, true, setOf("mp3")).size)
    }

    @Test
    fun `the fallback title is the file name without its extension`() {
        assertEquals("song", Playlist.titleOf(root.resolve("song.mp3")))
        assertEquals("no-dot", Playlist.titleOf(root.resolve("no-dot")))
    }
}
