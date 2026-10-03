package hivens.ui.chrome

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extra mouse buttons, as each toolkit numbers them. The X11 numbers were read
 * off a real XToolkit under Xvfb: server buttons 6 and 7, the sideways wheel, came
 * in as 4 and 5, and server buttons 8 and 9, the side buttons, as 6 and 7.
 */
class ExtraMouseButtonsTest {

    @Test
    fun `on X11 the sideways wheel scrolls and never pages history`() {
        assertEquals(ExtraButton.ScrollLeft, extraButton(4, x11 = true))
        assertEquals(ExtraButton.ScrollRight, extraButton(5, x11 = true))
    }

    @Test
    fun `on X11 the side buttons are back and forward`() {
        assertEquals(ExtraButton.Back, extraButton(6, x11 = true))
        assertEquals(ExtraButton.Forward, extraButton(7, x11 = true))
    }

    @Test
    fun `elsewhere the side buttons are back and forward as they were`() {
        assertEquals(ExtraButton.Back, extraButton(4, x11 = false))
        assertEquals(ExtraButton.Forward, extraButton(5, x11 = false))
        assertEquals(ExtraButton.Back, extraButton(6, x11 = false))
        assertEquals(ExtraButton.Forward, extraButton(7, x11 = false))
    }

    @Test
    fun `the first three buttons and anything past seven mean nothing here`() {
        for (b in listOf(1, 2, 3, 8, 12)) {
            assertEquals(ExtraButton.None, extraButton(b, x11 = true))
            assertEquals(ExtraButton.None, extraButton(b, x11 = false))
        }
    }
}
