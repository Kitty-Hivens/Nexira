package hivens.ui.audio

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Container tags -> [TrackInfo]. Pure, so the alias and fallback rules are
 * pinned without natives, an audio device or a tagged file on disk.
 */
class TrackInfoTest {

    private val file: Path = Path.of("/music/09 - a long file name.flac")

    @Test
    fun `lowercase id3 keys land on their fields`() {
        val info = trackInfoFrom(
            mapOf("title" to "Bus Stop", "artist" to "Jun Maeda", "album" to "Hikarizaka"),
            file,
        )
        assertEquals("Bus Stop", info.title)
        assertEquals("Jun Maeda", info.artist)
        assertEquals("Hikarizaka", info.album)
    }

    @Test
    fun `uppercase vorbis keys land on the same fields`() {
        val info = trackInfoFrom(mapOf("TITLE" to "Bus Stop", "ARTIST" to "Jun Maeda"), file)
        assertEquals("Bus Stop", info.title)
        assertEquals("Jun Maeda", info.artist)
    }

    @Test
    fun `an album artist stands in for a missing artist`() {
        assertEquals("Anri Kumaki", trackInfoFrom(mapOf("album_artist" to "Anri Kumaki"), file).artist)
        assertEquals("Anri Kumaki", trackInfoFrom(mapOf("ALBUMARTIST" to "Anri Kumaki"), file).artist)
        assertEquals("Anri Kumaki", trackInfoFrom(mapOf("performer" to "Anri Kumaki"), file).artist)
    }

    @Test
    fun `the first alias present wins over later ones`() {
        val info = trackInfoFrom(mapOf("artist" to "Soloist", "album_artist" to "Compilation"), file)
        assertEquals("Soloist", info.artist)
    }

    @Test
    fun `a blank tag counts as absent`() {
        // Players write empty frames; an empty artist line is worse than none.
        val info = trackInfoFrom(mapOf("title" to "   ", "artist" to "", "album" to "\t"), file)
        assertEquals("09 - a long file name", info.title, "a blank title falls through to the file name")
        assertNull(info.artist)
        assertNull(info.album)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val info = trackInfoFrom(mapOf("title" to "  Bus Stop\n", "artist" to " Jun Maeda "), file)
        assertEquals("Bus Stop", info.title)
        assertEquals("Jun Maeda", info.artist)
    }

    @Test
    fun `an untagged file is titled by its name without the extension`() {
        val info = trackInfoFrom(emptyMap(), file)
        assertEquals("09 - a long file name", info.title)
        assertNull(info.artist)
        assertNull(info.album)
        assertNull(info.artwork)
    }

    @Test
    fun `a name with no extension is taken whole`() {
        assertEquals("README", trackInfoFrom(emptyMap(), Path.of("/music/README")).title)
    }

    @Test
    fun `unreadable cover art degrades to no artwork`() {
        assertNull(decodeArtwork(ByteArray(0)))
        assertNull(decodeArtwork(byteArrayOf(1, 2, 3, 4)), "four bytes of noise are not a picture")
    }
}
