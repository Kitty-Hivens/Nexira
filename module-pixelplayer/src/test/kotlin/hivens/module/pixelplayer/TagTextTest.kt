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
    fun `shift-jis mojibake is left alone rather than guessed at`() {
        // Shift-JIS trail bytes span most of the byte range, so its round trip
        // proves nothing and accepting it corrupted real Latin-1 names. A title
        // that stays broken falls through to the file name; one silently turned
        // into kanji does not, and looks like the truth.
        val broken = misread("東方", "Shift_JIS")
        assertEquals(broken, TagText.repair(broken))
    }

    @Test
    fun `doubled umlauts are not eaten`() {
        // The false positive that survived the first guard: two adjacent high
        // bytes clear a run check, and Shift-JIS accepted them. Finnish and the
        // Nordic languages are full of these.
        assertEquals("Jää", TagText.repair("Jää"))
        assertEquals("ÅÄÖ", TagText.repair("ÅÄÖ"))
        assertEquals("åäö", TagText.repair("åäö"))
    }

    @Test
    fun `a name with a micro sign or a degree sign is legible`() {
        // Both are real group names in the music this is pointed at, and an
        // earlier threshold counted every character in U+0080..U+00BF as damage.
        assertTrue(TagText.isLegible("µ's"))
        assertTrue(TagText.isLegible("°C-ute"))
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
