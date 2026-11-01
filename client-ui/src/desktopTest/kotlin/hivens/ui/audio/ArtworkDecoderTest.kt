package hivens.ui.audio

import kotlin.test.Test
import kotlin.test.assertNull

class ArtworkDecoderTest {

    @Test
    fun `unreadable cover art degrades to no artwork`() {
        assertNull(decodeArtwork(ByteArray(0)))
        assertNull(decodeArtwork(byteArrayOf(1, 2, 3, 4)), "four bytes of noise are not a picture")
    }
}
