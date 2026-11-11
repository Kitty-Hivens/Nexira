package hivens.window

import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

// Frame geometry is in AWT user space throughout this module, which is
// numerically the Compose Dp value 1:1: density scales the render, never the
// frame, so a work area, a saved frame and a size the UI asks for compare
// without conversion.

/**
 * The size the window returns to when un-maximised. Not what it opens at,
 * see [initialWindowSize].
 */
val RESTORE_WINDOW_SIZE = Dimension(1100, 720)

/**
 * Work area of [gc]: the monitor's bounds minus the screen insets the WM
 * reports (taskbar / panel / menu bar + dock). A Wayland peer can report a
 * non-null gc with zero bounds before the surface negotiates; fall back to the
 * toolkit screen size then.
 */
fun screenWorkArea(gc: GraphicsConfiguration?): Rectangle {
    val tk = Toolkit.getDefaultToolkit()
    val b = gc?.bounds
    if (gc == null || b == null || b.width <= 0 || b.height <= 0) {
        val s = tk.screenSize
        val i = gc?.let { tk.getScreenInsets(it) }
        return if (i != null) Rectangle(i.left, i.top, s.width - i.left - i.right, s.height - i.top - i.bottom)
        else Rectangle(0, 0, s.width, s.height)
    }
    val i = tk.getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

/** The work area of every screen attached now, the primary first. Empty when headless. */
fun currentWorkAreas(): List<Rectangle> = runCatching {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = env.defaultScreenDevice
    (listOf(primary) + env.screenDevices.filter { it !== primary })
        .map { screenWorkArea(it.defaultConfiguration) }
        .filter { it.width > 0 && it.height > 0 }
}.getOrDefault(emptyList())

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
 * The result is raised to [floor] on each axis, so a very small display -- or a
 * panel eating most of one -- yields a window that is still usable rather than
 * one that is merely obedient.
 */
fun initialWindowSize(workArea: Rectangle, restore: Dimension, floor: Dimension): Dimension {
    if (workArea.width <= 0 || workArea.height <= 0) return Dimension(restore)
    return Dimension(maxOf(workArea.width, floor.width), maxOf(workArea.height, floor.height))
}

/**
 * The frame a window gets the first time it leaves maximised: [restore], centred
 * on the work area it was restored on, and never more than 80% of it on either
 * axis, so a small screen still gets a window that visibly is not maximised.
 */
fun restoredBounds(workArea: Rectangle, restore: Dimension): Rectangle {
    val w = minOf(restore.width, (workArea.width * RESTORE_CAP_FRACTION).toInt())
    val h = minOf(restore.height, (workArea.height * RESTORE_CAP_FRACTION).toInt())
    return Rectangle(workArea.x + (workArea.width - w) / 2, workArea.y + (workArea.height - h) / 2, w, h)
}

private const val RESTORE_CAP_FRACTION = 0.8f

/** An edge or a corner of the window, as a resize grabs it. */
enum class WindowEdge(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
    /** The AWT cursor that shows over it. */
    val cursor: Int,
    /** The EWMH `_NET_WM_MOVERESIZE` direction for it. */
    internal val ewmh: Int,
) {
    W(true, false, false, false, Cursor.W_RESIZE_CURSOR, 7),
    E(false, false, true, false, Cursor.E_RESIZE_CURSOR, 3),
    N(false, true, false, false, Cursor.N_RESIZE_CURSOR, 1),
    S(false, false, false, true, Cursor.S_RESIZE_CURSOR, 5),
    NW(true, true, false, false, Cursor.NW_RESIZE_CURSOR, 0),
    NE(false, true, true, false, Cursor.NE_RESIZE_CURSOR, 2),
    SW(true, false, false, true, Cursor.SW_RESIZE_CURSOR, 6),
    SE(false, false, true, true, Cursor.SE_RESIZE_CURSOR, 4);

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/**
 * The frame [start] becomes when [edge] is dragged by ([dx], [dy]) from where it
 * was pressed. The opposite edges stay put, and a side that would go below
 * [minWidth] or [minHeight] stops there, its opposite edge still in place.
 */
fun resizedBounds(start: Rectangle, edge: WindowEdge, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
    var left = start.x
    var top = start.y
    var right = start.x + start.width
    var bottom = start.y + start.height
    if (edge.left) left = minOf(left + dx, right - minWidth)
    if (edge.right) right = maxOf(right + dx, left + minWidth)
    if (edge.top) top = minOf(top + dy, bottom - minHeight)
    if (edge.bottom) bottom = maxOf(bottom + dy, top + minHeight)
    return Rectangle(left, top, right - left, bottom - top)
}
