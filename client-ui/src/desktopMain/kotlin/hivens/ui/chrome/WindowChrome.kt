package hivens.ui.chrome

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowState
import java.awt.MouseInfo

/**
 * Window-chrome plumbing for the custom (undecorated) title bar.
 *
 * Window-scoped handles -- the [WindowState], the AWT window, the close action --
 * live in AppShell's `Window {}` (a FrameWindowScope) and are NOT reachable from a
 * widget rendered deep under SlotRenderer. AppShell provides them through these
 * locals so the top-bar widgets (caption buttons, drag) can act on the window.
 */
val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }
val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow?> { null }

/** The window's real close path (tray-hide-or-exit + chaos dialog). Default no-op
 *  so a widget previewed outside the window does not crash. */
val LocalChromeClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Takes the window off screen without ending the process -- the same state the
 * tray hide sets, reachable from a widget that has no path to it.
 *
 * The launcher update needs this: the swap runs in a JVM shutdown hook, and the
 * native window is destroyed by process exit rather than before it, so without a
 * hide the launcher sits on screen not drawing for as long as the swap takes.
 * Default no-op, like the close path above.
 */
val LocalWindowHide = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Drags the OS window when the user drags this region (the bar's empty space).
 * Replaces FrameWindowScope.WindowDraggableArea, which a deep widget can't reach:
 * moves the AWT window by the pointer delta (works under X11 / XWayland).
 * Double-click toggles maximize via [onDoubleClick]. Dragging a maximized window
 * un-maximizes it under the cursor via [maximizer] (OS behavior). No-op when
 * [window] is null.
 *
 * The move happens only when [canMove] says the window manager allows it (see
 * [WindowCapabilities]). Where it does not, the window manager owns the window's
 * position, and a client-side move only fights it.
 */
fun Modifier.windowDragArea(
    window: ComposeWindow?,
    maximizer: WindowMaximizer? = null,
    canMove: Boolean = true,
    onDoubleClick: (() -> Unit)? = null,
    systemMove: (() -> Boolean)? = null,
): Modifier {
    if (window == null) return this
    val withDoubleClick = if (onDoubleClick == null) this else this.pointerInput(window, onDoubleClick) {
        detectTapGestures(onDoubleTap = { onDoubleClick() })
    }
    if (!canMove) return withDoubleClick
    return withDoubleClick
        .pointerInput(window, maximizer) {
            // Anchor the move against the ABSOLUTE screen cursor, not the Compose-local drag
            // delta. Feeding the local delta back into setLocation oscillates: moving the
            // window shifts the very coordinate frame the next delta is measured in, so each
            // frame over-corrects and the window jitters back and forth. Track the window's
            // start position plus the cursor's screen-space displacement instead -- stable
            // because the cursor's screen position is independent of where the window is.
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown()
                    var startMouse = MouseInfo.getPointerInfo()?.location ?: continue
                    var startWindow = window.location
                    // Un-maximize on the FIRST drag movement, not on the press: a plain click
                    // or a double-click on the maximized bar must be left to those gestures
                    // (a press-time restore would eat the click and break double-click-to-
                    // restore). On that first move, re-anchor to the restored frame so the
                    // move continues smoothly from under the cursor.
                    var restored = false
                    // Where the window manager can take the move over (see
                    // X11SystemDrag), the first movement hands it the rest of the drag,
                    // after a maximised window was restored under the pointer as below.
                    var handedOver = false
                    var asked = false
                    // Nothing moves until the pointer has travelled past the touch slop:
                    // a click that jitters by a pixel stays a click, and the second half
                    // of a double click is not taken by a window manager's move grab.
                    var moving = false
                    val slop = viewConfiguration.touchSlop
                    drag(down.id) { change ->
                        change.consume()
                        if (handedOver) return@drag
                        if (!moving) {
                            if ((change.position - down.position).getDistance() < slop) return@drag
                            moving = true
                        }
                        if (systemMove != null && !asked && (restored || maximizer?.maximized != true)) {
                            asked = true
                            handedOver = systemMove()
                            if (handedOver) return@drag
                        }
                        val now = MouseInfo.getPointerInfo()?.location ?: return@drag
                        if (!restored && maximizer?.maximized == true) {
                            maximizer.unmaximizeUnderCursor(now.x, now.y)
                            startWindow = window.location
                            startMouse = now
                        }
                        restored = true
                        window.setLocation(
                            startWindow.x + (now.x - startMouse.x),
                            startWindow.y + (now.y - startMouse.y),
                        )
                    }
                }
            }
        }
}
