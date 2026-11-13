package hivens.window

/**
 * A launcher window's state, as [WindowHost] reads it from the system. Never
 * set by the launcher itself: a command goes to the system, and this changes
 * when the system says the window changed.
 */
data class WindowStatus(val maximized: Boolean, val minimized: Boolean)

/**
 * A window's size in AWT user space, which is the Compose Dp value 1:1 (see
 * [initialWindowSize]).
 */
data class WindowSize(val width: Int, val height: Int)

/**
 * What the window manager lets the user do with the launcher's window, as the
 * window manager itself declares it.
 *
 * The window draws its own frame, so every caption button, the drag lane and the
 * resize grips are promises made on the window manager's behalf. Each one is
 * offered only when the window manager has said it will honour it. On X11 that
 * is EWMH `_NET_WM_ALLOWED_ACTIONS`, kept per window and changed at will, so this
 * is live: a window manager may take resizing away from a maximised window and
 * hand it back later. On Windows and macOS the system always allows all of it.
 *
 * A window manager that declares nothing at all promises nothing, and gets
 * [Nothing]. A compositor that manages every window itself, the way Hyprland
 * does, is that case: it moves, sizes and maximises windows from its own
 * bindings and tells the client none of it, so a button for any of those would
 * be a guess.
 */
data class WindowActions(
    val move: Boolean,
    val resize: Boolean,
    val maximize: Boolean,
    val minimize: Boolean,
) {
    companion object {
        val Everything = WindowActions(move = true, resize = true, maximize = true, minimize = true)
        val Nothing = WindowActions(move = false, resize = false, maximize = false, minimize = false)
    }
}
