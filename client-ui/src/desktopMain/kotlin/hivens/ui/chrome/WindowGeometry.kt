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
const val MIN_WINDOW_WIDTH_DP = 800
const val MIN_WINDOW_HEIGHT_DP = 600

/**
 * The size the window returns to when un-maximised. Not what it opens at,
 * see [initialWindowSize].
 */
val RESTORE_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

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
 * size. This is the size of a window that opens maximised. One left as a window
 * opens at the frame it was left with (see [openingFrame]).
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

/**
 * The frame a window gets the first time it leaves maximised: [restore], centred
 * on the work area it was restored on, and never more than 80% of it on either
 * axis, so a small screen still gets a window that visibly is not maximised.
 */
fun restoredBounds(workArea: Rectangle, restore: DpSize): Rectangle {
    val w = minOf(restore.width.value.toInt(), (workArea.width * RESTORE_CAP_FRACTION).toInt())
    val h = minOf(restore.height.value.toInt(), (workArea.height * RESTORE_CAP_FRACTION).toInt())
    return Rectangle(workArea.x + (workArea.width - w) / 2, workArea.y + (workArea.height - h) / 2, w, h)
}

private const val RESTORE_CAP_FRACTION = 0.8f
