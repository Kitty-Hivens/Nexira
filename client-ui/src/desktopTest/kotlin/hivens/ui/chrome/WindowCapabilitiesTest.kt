package hivens.ui.chrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the window manager's `_NET_WM_ALLOWED_ACTIONS` turns into, and what the
 * caption buttons make of it.
 *
 * The action lists are the ones window managers actually write: Openbox's was
 * read off a live window under Xvfb, the others are what their sources set.
 */
class WindowCapabilitiesTest {

    private val openbox = listOf(
        "_NET_WM_ACTION_CHANGE_DESKTOP", "_NET_WM_ACTION_SHADE", "_NET_WM_ACTION_CLOSE",
        "_NET_WM_ACTION_MOVE", "_NET_WM_ACTION_MINIMIZE", "_NET_WM_ACTION_RESIZE",
        "_NET_WM_ACTION_FULLSCREEN", "_NET_WM_ACTION_MAXIMIZE_HORZ", "_NET_WM_ACTION_MAXIMIZE_VERT",
        "_NET_WM_ACTION_ABOVE", "_NET_WM_ACTION_BELOW", "_OB_WM_ACTION_UNDECORATE",
    )

    @Test
    fun `a floating window manager that allows everything gets everything`() {
        assertEquals(WindowCapabilities.Everything, capabilitiesFromAllowedActions(openbox))
    }

    @Test
    fun `an empty list allows nothing`() {
        assertEquals(WindowCapabilities.Nothing, capabilitiesFromAllowedActions(emptyList()))
    }

    @Test
    fun `maximising along one axis is not the maximise button`() {
        val horizontalOnly = openbox - "_NET_WM_ACTION_MAXIMIZE_VERT"
        val caps = capabilitiesFromAllowedActions(horizontalOnly)
        assertFalse(caps.maximize, "the button maximises both ways, so one axis is not enough")
        assertTrue(caps.move && caps.resize && caps.minimize)
    }

    @Test
    fun `a window the manager will not resize keeps the actions it still allows`() {
        // A fixed-size window, or a maximised one under a window manager that takes
        // resizing away while maximised.
        val caps = capabilitiesFromAllowedActions(openbox - "_NET_WM_ACTION_RESIZE")
        assertEquals(WindowCapabilities(move = true, resize = false, maximize = true, minimize = true), caps)
    }

    @Test
    fun `actions the frame has no control for change nothing`() {
        val caps = capabilitiesFromAllowedActions(listOf("_NET_WM_ACTION_SHADE", "_NET_WM_ACTION_CLOSE", "_NET_WM_ACTION_ABOVE"))
        assertEquals(WindowCapabilities.Nothing, caps)
    }

    @Test
    fun `a window is maximised when the window manager says so along both axes`() {
        // What an XWayland window read off a live session carries, maximised by the shell and focused.
        assertTrue(maximizedFromState(listOf("_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ", "_NET_WM_STATE_FOCUSED")))
        assertFalse(maximizedFromState(listOf("_NET_WM_STATE_MAXIMIZED_VERT")), "one axis is not the window the button maximises")
        assertFalse(maximizedFromState(listOf("_NET_WM_STATE_FOCUSED")))
        assertFalse(maximizedFromState(emptyList()), "a window with no state written has none")
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
