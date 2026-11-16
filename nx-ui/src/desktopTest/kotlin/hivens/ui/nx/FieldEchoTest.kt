package hivens.ui.nx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A field kept in step with its caller's string, typed into faster than the caller recomposes. */
class FieldEchoTest {

    @Test
    fun `the caller catching up with the typing does not erase what was typed since`() {
        val echo = FieldEcho()
        echo.sending("V")
        echo.sending("Va")
        echo.sending("Van")
        // The caller recomposes with each value in turn while the field already shows "Van".
        assertNull(echo.onValue("V", shown = "Van"))
        assertNull(echo.onValue("Va", shown = "Van"))
        assertNull(echo.onValue("Van", shown = "Van"))
    }

    @Test
    fun `a value the field never sent replaces what it shows`() {
        val echo = FieldEcho()
        echo.sending("vanilla")
        assertEquals("VANILLA", echo.onValue("VANILLA", shown = "vanilla"), "the caller rewrote the edit")
        assertEquals("", echo.onValue("", shown = "VANILLA"), "the caller cleared the field from outside")
    }

    @Test
    fun `an erasure back to a value the caller has not caught up from is still an edit to send`() {
        val echo = FieldEcho()
        echo.sending("ab")
        // The caller still shows "a" when the field is erased back to "a": the edit the
        // caller is about to take is "ab", so "a" differs from it and goes out.
        assertEquals("ab", echo.lastSent())
        echo.sending("a")
        assertNull(echo.onValue("ab", shown = "a"), "the caller catching up with the older edit")
        assertNull(echo.onValue("a", shown = "a"))
        assertNull(echo.lastSent(), "nothing is pending once the caller has caught up")
    }

    @Test
    fun `once the caller holds what the field shows nothing is still on its way`() {
        val echo = FieldEcho()
        // A letter typed and erased before the next frame: both went out.
        echo.sending("x")
        echo.sending("")
        echo.clear()
        assertNull(echo.lastSent())
        assertEquals("x", echo.onValue("x", shown = ""), "a later x from the caller is its own, not an echo")
    }
}
