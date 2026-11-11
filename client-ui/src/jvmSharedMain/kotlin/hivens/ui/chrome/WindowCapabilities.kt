package hivens.ui.chrome

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

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
@Immutable
data class WindowCapabilities(
    val move: Boolean,
    val resize: Boolean,
    val maximize: Boolean,
    val minimize: Boolean,
) {
    /** Whether the window manager leaves anything at all to the client's own frame. */
    val any: Boolean get() = move || resize || maximize || minimize

    companion object {
        val Everything = WindowCapabilities(move = true, resize = true, maximize = true, minimize = true)
        val Nothing = WindowCapabilities(move = false, resize = false, maximize = false, minimize = false)
    }
}

/**
 * The launcher window's [WindowCapabilities]. [WindowCapabilities.Everything] by
 * default, which is what a preview outside a window and a platform without a
 * frame of its own both want: neither has a frame for it to gate.
 */
val LocalWindowCapabilities = compositionLocalOf { WindowCapabilities.Everything }

/**
 * Whether the window manager lets the launcher draw its own frame at all, decided
 * once when the window is created, since `undecorated` cannot change after that.
 * True by default for the same reason as [LocalWindowCapabilities].
 */
val LocalCustomChromeAvailable = compositionLocalOf { true }
