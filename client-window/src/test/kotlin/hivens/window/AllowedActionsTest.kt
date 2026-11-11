package hivens.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the window manager's `_NET_WM_ALLOWED_ACTIONS` and `_NET_WM_STATE` turn into.
 *
 * The action lists are the ones window managers actually write: Openbox's was
 * read off a live window under Xvfb, the others are what their sources set.
 */
class AllowedActionsTest {

    private val openbox = listOf(
        "_NET_WM_ACTION_CHANGE_DESKTOP", "_NET_WM_ACTION_SHADE", "_NET_WM_ACTION_CLOSE",
        "_NET_WM_ACTION_MOVE", "_NET_WM_ACTION_MINIMIZE", "_NET_WM_ACTION_RESIZE",
        "_NET_WM_ACTION_FULLSCREEN", "_NET_WM_ACTION_MAXIMIZE_HORZ", "_NET_WM_ACTION_MAXIMIZE_VERT",
        "_NET_WM_ACTION_ABOVE", "_NET_WM_ACTION_BELOW", "_OB_WM_ACTION_UNDECORATE",
    )

    @Test
    fun `a floating window manager that allows everything gets everything`() {
        assertEquals(WindowActions.Everything, actionsFromAllowedActions(openbox))
    }

    @Test
    fun `an empty list allows nothing`() {
        assertEquals(WindowActions.Nothing, actionsFromAllowedActions(emptyList()))
    }

    @Test
    fun `maximising along one axis is not the maximise button`() {
        val horizontalOnly = openbox - "_NET_WM_ACTION_MAXIMIZE_VERT"
        val actions = actionsFromAllowedActions(horizontalOnly)
        assertFalse(actions.maximize, "the button maximises both ways, so one axis is not enough")
        assertTrue(actions.move && actions.resize && actions.minimize)
    }

    @Test
    fun `a window the manager will not resize keeps the actions it still allows`() {
        // A fixed-size window, or a maximised one under a window manager that takes
        // resizing away while maximised.
        val actions = actionsFromAllowedActions(openbox - "_NET_WM_ACTION_RESIZE")
        assertEquals(WindowActions(move = true, resize = false, maximize = true, minimize = true), actions)
    }

    @Test
    fun `actions the frame has no control for change nothing`() {
        val actions = actionsFromAllowedActions(listOf("_NET_WM_ACTION_SHADE", "_NET_WM_ACTION_CLOSE", "_NET_WM_ACTION_ABOVE"))
        assertEquals(WindowActions.Nothing, actions)
    }

    @Test
    fun `a window is maximised when the window manager says so along both axes`() {
        // What an XWayland window read off a live session carries, maximised by the shell and focused.
        assertTrue(maximizedFromState(listOf("_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ", "_NET_WM_STATE_FOCUSED")))
        assertFalse(maximizedFromState(listOf("_NET_WM_STATE_MAXIMIZED_VERT")), "one axis is not the window the button maximises")
        assertFalse(maximizedFromState(listOf("_NET_WM_STATE_FOCUSED")))
        assertFalse(maximizedFromState(emptyList()), "a window with no state written has none")
    }
}
