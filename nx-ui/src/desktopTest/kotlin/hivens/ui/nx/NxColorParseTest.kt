package hivens.ui.nx

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract of [parseHexOrNull]: one format (#RRGGBB / #AARRGGBB), null on anything
 * else. Pure -- pins the "what is a colour" rule NxColorField enforces, with no
 * renderer.
 */
class NxColorParseTest {

    @Test
    fun `six-digit hex is opaque rgb`() {
        assertEquals(Color(0xFFFF0000), parseHexOrNull("#FF0000"))
        assertEquals(Color(0xFF00FF00), parseHexOrNull("00FF00"))
    }

    @Test
    fun `eight-digit hex keeps its alpha`() {
        assertEquals(Color(0x80FF0000), parseHexOrNull("#80FF0000"))
    }

    @Test
    fun `hash and surrounding space are tolerated`() {
        assertEquals(Color(0xFFABCDEF), parseHexOrNull("  #ABCDEF  "))
        assertEquals(Color(0xFFABCDEF), parseHexOrNull("ABCDEF"))
    }

    @Test
    fun `blank, wrong length, or non-hex is null`() {
        assertNull(parseHexOrNull(""))
        assertNull(parseHexOrNull("#FFF"))
        assertNull(parseHexOrNull("12345"))
        assertNull(parseHexOrNull("xyzxyz"))
    }
}
