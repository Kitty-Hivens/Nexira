package hivens.ui.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiColorTest {

    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun `a line without escapes is returned unchanged with no runs`() {
        val p = parseAnsi("[19:42:13] [main/INFO]: hello")
        assertEquals("[19:42:13] [main/INFO]: hello", p.text)
        assertTrue(p.runs.isEmpty())
    }

    @Test
    fun `a foreground colour becomes a run over the plain text`() {
        // ESC[36m colours "[main]" cyan, ESC[m resets.
        val p = parseAnsi("$esc[36m[main]$esc[m rest")
        assertEquals("[main] rest", p.text)
        assertEquals(1, p.runs.size)
        val run = p.runs.single()
        assertEquals(0, run.start)
        assertEquals(6, run.end) // "[main]"
        assertEquals("#00cdcd", run.colorHex)
        assertEquals(false, run.bold)
    }

    @Test
    fun `bold with no colour yields a bold-only run`() {
        val p = parseAnsi("${esc}[1mbold${esc}[22m normal")
        assertEquals("bold normal", p.text)
        assertEquals(1, p.runs.size)
        assertEquals("bold", p.text.substring(p.runs[0].start, p.runs[0].end))
        assertEquals(null, p.runs[0].colorHex)
        assertTrue(p.runs[0].bold)
    }

    @Test
    fun `combined bold and colour, reset by SGR 0`() {
        val p = parseAnsi("$esc[1;31mx$esc[0my")
        assertEquals("xy", p.text)
        assertEquals(1, p.runs.size)
        assertEquals("#cd0000", p.runs[0].colorHex)
        assertTrue(p.runs[0].bold)
        assertEquals(0, p.runs[0].start)
        assertEquals(1, p.runs[0].end)
    }

    @Test
    fun `truecolour 38 2 maps to an exact hex`() {
        val p = parseAnsi("$esc[38;2;10;20;30mX$esc[0m")
        assertEquals("X", p.text)
        assertEquals("#0a141e", p.runs.single().colorHex)
    }

    @Test
    fun `256-colour 38 5 maps through the xterm cube`() {
        // 196 is the pure-red corner of the 6x6x6 cube.
        val p = parseAnsi("$esc[38;5;196mX$esc[0m")
        assertEquals("X", p.text)
        assertEquals("#ff0000", p.runs.single().colorHex)
    }

    @Test
    fun `bright foreground uses the bright palette`() {
        val p = parseAnsi("$esc[93mY$esc[0m")
        assertEquals("Y", p.text)
        assertEquals("#ffff55", p.runs.single().colorHex) // bright yellow
    }

    @Test
    fun `an OSC window-title sequence is dropped without a run`() {
        val p = parseAnsi("$esc]0;some title${bel}after")
        assertEquals("after", p.text)
        assertTrue(p.runs.isEmpty())
    }

    @Test
    fun `a Cleanroom-style coloured line keeps the words and colours the pieces`() {
        val raw = "$esc[93m[01:14:00]$esc[m $esc[36m[main|Foundation]$esc[m done"
        val p = parseAnsi(raw)
        assertEquals("[01:14:00] [main|Foundation] done", p.text)
        assertEquals(2, p.runs.size)
        assertEquals("[01:14:00]", p.text.substring(p.runs[0].start, p.runs[0].end))
        assertEquals("[main|Foundation]", p.text.substring(p.runs[1].start, p.runs[1].end))
    }
}
