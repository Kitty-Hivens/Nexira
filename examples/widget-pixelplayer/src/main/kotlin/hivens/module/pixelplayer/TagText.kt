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

    /**
     * UTF-8 and nothing else, and the restriction is the point.
     *
     * The round trip is only evidence when the candidate charset is strict.
     * UTF-8 is: its byte sequences are structured, an invalid one decodes to
     * U+FFFD, and re-encoding has to reproduce the bytes exactly. Shift-JIS and
     * windows-1251 are not. Shift-JIS trail bytes span most of the byte range,
     * so a Finnish name like "Jaa" with two adjacent umlauts round-trips cleanly
     * into kanji; windows-1251 is a full 8-bit table and accepts nearly anything
     * that reaches it, which turned a truncated UTF-8 title into fluent-looking
     * Cyrillic wreckage.
     *
     * So mojibake in those encodings is left alone rather than guessed at. A
     * title that stays broken falls through to the file name; a Swedish artist
     * silently turned into kanji does not, and looks like the truth.
     */
    private val candidate: Charset = StandardCharsets.UTF_8

    fun repair(text: String): String {
        if (text.any { it.code > 0xFF }) return text
        // Mojibake comes in RUNS: a misread UTF-8 character is two or three high
        // bytes in a row, while genuine Latin-1 has isolated accented letters
        // among ASCII. A cheap precheck ahead of the real test below.
        if (!hasHighByteRun(text)) return text

        val raw = text.toByteArray(StandardCharsets.ISO_8859_1)
        val decoded = String(raw, candidate)
        return if (!decoded.contains('\uFFFD') && decoded.toByteArray(candidate).contentEquals(raw)) decoded else text
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
        // Only replacement characters and C1 controls count as damage. An earlier
        // version treated all of U+0080..U+00BF that way and threw out real names:
        // "µ's" and "°C-ute" are groups in exactly the music this is pointed at,
        // and both lost to the file name.
        val suspect = text.count { it == '�' || it.code in 0x80..0x9F }
        return suspect * 4 < text.length
    }
}
