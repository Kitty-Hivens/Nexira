package hivens.themes

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** The helper the reading floor is measured with, held to values worked out by hand. */
class ApcaTest {

    @Test
    fun `light text on a dark ground is negative`() {
        assertEquals(-42.0, apcaLc(Color(0xFFA6A6A6), Color(0xFF474747)), 1.0)
        assertEquals(-41.0, apcaLc(Color(0xFF939293), Color(0xFF222222)), 1.0)
    }

    @Test
    fun `dark text on a light ground is positive`() {
        assertEquals(105.0, apcaLc(Color(0xFF111111), Color(0xFFFFFFFF)), 1.0)
        assertEquals(53.0, apcaLc(Color(0xFF5C5C5C), Color(0xFFCCCCCC)), 1.0)
    }

    @Test
    fun `a colour on itself has no contrast`() {
        assertEquals(0.0, apcaLc(Color(0xFF777777), Color(0xFF777777)))
    }
}
