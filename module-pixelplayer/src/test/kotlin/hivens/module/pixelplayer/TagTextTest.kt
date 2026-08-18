package hivens.module.pixelplayer

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagTextTest {

    /** What a decoder does when it reads UTF-8 bytes as Latin-1. */
    private fun misread(text: String, charsetName: String = "UTF-8"): String =
        String(text.toByteArray(java.nio.charset.Charset.forName(charsetName)), StandardCharsets.ISO_8859_1)

    @Test
    fun `utf-8 read as latin-1 is recovered exactly`() {
        val original = "初音ミク / Quite!Quiet!!"
        assertEquals(original, TagText.repair(misread(original)))
    }

    @Test
    fun `cyrillic utf-8 read as latin-1 is recovered`() {
        val original = "Пляска смерти"
        assertEquals(original, TagText.repair(misread(original)))
    }

    @Test
    fun `shift-jis read as latin-1 is recovered`() {
        val original = "東方"
        assertEquals(original, TagText.repair(misread(original, "Shift_JIS")))
    }

    @Test
    fun `plain ascii is left alone`() {
        assertEquals("Quite!Quiet!!", TagText.repair("Quite!Quiet!!"))
    }

    @Test
    fun `text that is already correct is not folded back and destroyed`() {
        // The dangerous case: a decoder that got it right hands back real
        // characters above U+00FF. Folding those into Latin-1 bytes would be
        // lossy, so the repair must decline.
        val correct = "初音ミク"
        assertEquals(correct, TagText.repair(correct))
    }

    @Test
    fun `genuine latin-1 survives, even where a candidate charset would accept it`() {
        // The false positive this guards: an o-umlaut folds to 0xF6, which is a
        // valid Shift-JIS lead byte, and the following "r" is a valid trail. The
        // round trip passes and the name would become kanji. An isolated high
        // byte is not mojibake -- mojibake arrives in runs.
        assertEquals("Björk", TagText.repair("Björk"))
        assertEquals("Café", TagText.repair("Café"))
        assertEquals("Motörhead", TagText.repair("Motörhead"))
    }

    @Test
    fun `wreckage is reported illegible so a caller can fall back`() {
        assertFalse(TagText.isLegible(""))
        assertFalse(TagText.isLegible("����"))
        assertTrue(TagText.isLegible("初音ミク"))
        assertTrue(TagText.isLegible("Quite!Quiet!!"))
    }
}
