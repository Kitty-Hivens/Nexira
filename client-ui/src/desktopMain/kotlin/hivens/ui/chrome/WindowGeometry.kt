package hivens.ui.chrome

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Rectangle

/**
 * Smallest window the shell lays out in: below this the rails and the centre
 * pane stop fitting side by side.
 *
 * AWT user space is numerically the Compose Dp value here -- density scales the
 * render, never the frame geometry -- so these are comparable to a work-area
 * rectangle without conversion.
 */
const val MIN_WINDOW_WIDTH_DP = 960
const val MIN_WINDOW_HEIGHT_DP = 600

/**
 * The size the window is created at: the work area, whatever the desktop is.
 *
 * Compose draws one frame before showing the window, sized to what was asked
 * for. Ask for less than the window ends up being, and that frame covers only
 * part of it -- the rest stays raw native surface, which reads as white, until
 * the next frame lands. Recorded on Hyprland at 120 fps: asking for 1100x720
 * while the compositor tiled the window full-screen gave roughly 1.9 seconds of
 * white around a correctly drawn 1100x720 corner. Asking for the work area gave
 * none, on that desktop and on a floating one.
 *
 * Hence no window-manager branch. A floating desktop maximises the window right
 * after showing it, so the work area is what it becomes; a tiling one assigns
 * the frame itself, and the work area lands far closer to that than any invented
 * size. The launcher opens full-size either way -- a floating launcher window
 * was a 1.x shape.
 *
 * [restore] is the size the window returns to when un-maximised, and the
 * fallback for a work area a display peer has not negotiated yet.
 *
 * The result is clamped to the minimum the shell can lay out in, so a very small
 * display -- or a panel eating most of one -- yields a window that is still
 * usable rather than one that is merely obedient.
 */
fun initialWindowSize(workArea: Rectangle, restore: DpSize): DpSize {
    if (workArea.width <= 0 || workArea.height <= 0) return restore
    return DpSize(
        width = maxOf(workArea.width, MIN_WINDOW_WIDTH_DP).dp,
        height = maxOf(workArea.height, MIN_WINDOW_HEIGHT_DP).dp,
    )
}
