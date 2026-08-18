package hivens.module.pixelplayer

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Repairs a tag that arrived decoded with the wrong charset.
 *
 * ID3v1 carries no encoding field at all and ID3v2.3 defaults to ISO-8859-1, so
 * a decoder reading either faithfully hands back Latin-1 text for tags that were
 * actually written in something else. A Japanese title comes out as a run of
 * accented Latin letters, which is what a folder of rhythm-game songs is full of.
 *
 * The repair is not a guess. ISO-8859-1 maps 0x00..0xFF onto U+0000..U+00FF one
 * for one, so a string that came from that decoding can be folded back into the
 * exact bytes it was built from. Those bytes are then tested against a candidate
 * charset by decoding and RE-ENCODING: only a charset that reproduces the bytes
 * exactly can have been the original, so a wrong candidate is rejected rather
 * than believed.
 */
internal object TagText {

    private val SHIFT_JIS: Charset? = runCatching { Charset.forName("Shift_JIS") }.getOrNull()
    private val WINDOWS_1251: Charset? = runCatching { Charset.forName("windows-1251") }.getOrNull()

    /**
     * Candidates in the order they are tried. UTF-8 first because its round trip
     * is the strictest: almost nothing that is not UTF-8 survives it, so a pass
     * there is close to proof rather than preference.
     */
    private val candidates: List<Charset> = listOfNotNull(StandardCharsets.UTF_8, SHIFT_JIS, WINDOWS_1251)

    fun repair(text: String): String {
        // Nothing above U+00FF means the string never went through a Latin-1
        // decode -- either it is plain ASCII, or the decoder already got it right
        // and folding it back would destroy correct text.
        if (text.any { it.code > 0xFF }) return text
        // Mojibake comes in RUNS: a misread UTF-8 character is two or three high
        // bytes in a row, a misread Shift-JIS one is two. Genuine Latin-1 text
        // has isolated accented letters among ASCII. Without this the repair eats
        // real names -- "Bjork" with an o-umlaut folds to a byte that is a valid
        // Shift-JIS lead, the trailing "r" is a valid trail, the round trip
        // passes, and the name becomes kanji.
        if (!hasHighByteRun(text)) return text

        val raw = text.toByteArray(StandardCharsets.ISO_8859_1)
        for (charset in candidates) {
            val decoded = String(raw, charset)
            if (!decoded.contains('�') && decoded.toByteArray(charset).contentEquals(raw)) {
                return decoded
            }
        }
        return text
    }

    /** Two or more consecutive characters in the Latin-1 high range. */
    private fun hasHighByteRun(text: String): Boolean {
        var run = 0
        for (ch in text) {
            if (ch.code in 0x80..0xFF) {
                run++
                if (run >= 2) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /**
     * Whether a repaired string is worth showing at all.
     *
     * A tag can be damaged past recovery -- truncated mid-sequence, or written in
     * a charset nothing here carries -- and printing the wreckage is worse than
     * printing the file name, because the wreckage looks like the track is called
     * that.
     */
    fun isLegible(text: String): Boolean {
        if (text.isBlank()) return false
        val suspect = text.count { it == '�' || (it.code in 0x80..0xBF) }
        return suspect * 4 < text.length
    }
}
