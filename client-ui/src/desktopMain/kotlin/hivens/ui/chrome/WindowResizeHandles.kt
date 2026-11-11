package hivens.ui.chrome

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle

/**
 * Edge/corner resize grips for the undecorated window -- undecorated drops the
 * native resize border, so we synthesize 8 thin hit-zones. Stand down when
 * [maximized] (a maximized window is not resizable) and when [canResize] says the
 * window manager does not allow it (see [WindowCapabilities]): there it owns the
 * size, and grips would let the user drag against it.
 *
 * A grip resizes the frame from where the pointer is on the screen relative to
 * where it was pressed. Not from the drag
 * delta Compose reports: that is measured inside the grip, and the grip moves
 * with the edge it resizes, so each step was measured against a frame the last
 * step had already moved, and the edge jittered back and forth behind the
 * pointer. The frame is set directly rather than through [WindowState], which
 * would cost a recomposition per mouse step; the state follows the frame on its
 * own.
 *
 * Where the window manager takes resizes over ([systemResize], see
 * [X11SystemDrag]) a press on a grip hands it the resize instead.
 *
 * Best-effort under Wayland/XWayland: AWT setBounds is honored but the compositor
 * owns the surface, so a fast drag can lag. Overlay this LAST so the grips sit
 * above content; the 6dp band is small enough not to steal edge clicks.
 */
@Composable
internal fun WindowResizeHandles(
    state: WindowState,
    window: ComposeWindow?,
    minSize: DpSize,
    maximized: Boolean,
    canResize: Boolean,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp,
    systemResize: ((ResizeEdge) -> Boolean)? = null,
) {
    if (!canResize || window == null) return
    if (maximized) return
    if (state.placement != WindowPlacement.Floating) return
    Box(modifier.fillMaxSize()) {
        // Edges first, then corners -- corners win where they overlap an edge.
        Grip(ResizeEdge.N, Alignment.TopCenter, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.S, Alignment.BottomCenter, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.W, Alignment.CenterStart, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.E, Alignment.CenterEnd, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.NW, Alignment.TopStart, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.NE, Alignment.TopEnd, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.SW, Alignment.BottomStart, window, minSize, thickness, systemResize)
        Grip(ResizeEdge.SE, Alignment.BottomEnd, window, minSize, thickness, systemResize)
    }
}

internal enum class ResizeEdge(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
    val cursor: Int,
    /** The EWMH `_NET_WM_MOVERESIZE` direction for this edge. */
    val ewmh: Int,
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

@Composable
private fun BoxScope.Grip(
    edge: ResizeEdge,
    align: Alignment,
    window: ComposeWindow,
    minSize: DpSize,
    thickness: Dp,
    systemResize: ((ResizeEdge) -> Boolean)?,
) {
    val sizeMod = when {
        edge.isCorner            -> Modifier.size(thickness)
        edge.left || edge.right  -> Modifier.fillMaxHeight().width(thickness)
        else                     -> Modifier.fillMaxWidth().height(thickness)
    }
    Box(
        Modifier
            .align(align)
            .then(sizeMod)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(edge.cursor)))
            .pointerInput(edge, window) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        if (systemResize?.invoke(edge) == true) continue
                        val startMouse = MouseInfo.getPointerInfo()?.location ?: continue
                        val start = window.bounds
                        // AWT user space is the dp value 1:1 for frame geometry, see WindowGeometry.
                        val minW = minSize.width.value.toInt()
                        val minH = minSize.height.value.toInt()
                        drag(down.id) { change ->
                            change.consume()
                            val now = MouseInfo.getPointerInfo()?.location ?: return@drag
                            window.bounds = resizedBounds(start, edge, now.x - startMouse.x, now.y - startMouse.y, minW, minH)
                        }
                    }
                }
            },
    )
}

/**
 * The frame [start] becomes when [edge] is dragged by ([dx], [dy]) from where it
 * was pressed. The opposite edges stay put, and a side that would go below
 * [minWidth] or [minHeight] stops there, its opposite edge still in place.
 */
internal fun resizedBounds(start: Rectangle, edge: ResizeEdge, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
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
