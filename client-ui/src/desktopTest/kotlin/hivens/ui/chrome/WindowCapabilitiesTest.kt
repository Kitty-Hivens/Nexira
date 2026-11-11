package hivens.ui.chrome

import hivens.window.WindowActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the caption buttons make of what the window manager allows. */
class WindowCapabilitiesTest {

    @Test
    fun `the window manager's allowed actions reach the frame unchanged`() {
        assertEquals(WindowCapabilities.Everything, WindowActions.Everything.toCapabilities())
        assertEquals(WindowCapabilities.Nothing, WindowActions.Nothing.toCapabilities())
        assertEquals(
            WindowCapabilities(move = true, resize = false, maximize = true, minimize = false),
            WindowActions(move = true, resize = false, maximize = true, minimize = false).toCapabilities(),
        )
    }

    @Test
    fun `auto shows the buttons wherever the window manager leaves anything to the frame`() {
        assertTrue(WindowControlsMode.Auto.resolved(WindowCapabilities.Everything))
        assertTrue(WindowControlsMode.Auto.resolved(WindowCapabilities.Nothing.copy(minimize = true)))
        assertFalse(WindowControlsMode.Auto.resolved(WindowCapabilities.Nothing))
    }

    @Test
    fun `show and hide are the user's word over what the window manager declares`() {
        assertTrue(WindowControlsMode.Show.resolved(WindowCapabilities.Nothing))
        assertFalse(WindowControlsMode.Hide.resolved(WindowCapabilities.Everything))
    }
}
