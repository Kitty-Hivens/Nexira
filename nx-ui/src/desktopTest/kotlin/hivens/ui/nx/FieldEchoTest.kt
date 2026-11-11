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
    fun `an edit the caller refused is taken back`() {
        val echo = FieldEcho()
        echo.sending("12a")
        // The caller kept "12": its value never moved.
        assertEquals("12", echo.settle(typed = "12a", before = "12", value = "12", shown = "12a"))
    }

    @Test
    fun `nothing is taken back while the caller has not answered yet but more was typed`() {
        val echo = FieldEcho()
        echo.sending("Va")
        echo.sending("Van")
        assertNull(echo.settle(typed = "Va", before = "V", value = "V", shown = "Van"))
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
    fun `an accepted edit is left alone`() {
        val echo = FieldEcho()
        echo.sending("Va")
        assertNull(echo.settle(typed = "Va", before = "V", value = "Va", shown = "Va"))
    }
}
