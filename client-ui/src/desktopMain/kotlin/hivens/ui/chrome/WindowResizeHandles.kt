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
import androidx.compose.ui.unit.dp
import hivens.window.WindowEdge
import hivens.window.WindowHost
import hivens.window.resizedBounds
import java.awt.Cursor
import java.awt.MouseInfo

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
 * pointer. The frame is set directly rather than through Compose's window state,
 * which would cost a recomposition per mouse step; the state follows the frame
 * on its own.
 *
 * A side stops at the frame's own minimum size, read at the press: the one
 * [WindowHost.keepMinimumSize] cut to the screen the window is on, not the shell's
 * design minimum, which on a small screen is taller than the screen.
 *
 * Where the window manager takes resizes over ([WindowHost.beginResize]) a press
 * on a grip hands it the resize instead.
 *
 * Best-effort under Wayland/XWayland: AWT setBounds is honored but the compositor
 * owns the surface, so a fast drag can lag. Overlay this LAST so the grips sit
 * above content; the 6dp band is small enough not to steal edge clicks.
 */
@Composable
internal fun WindowResizeHandles(
    window: ComposeWindow?,
    host: WindowHost,
    maximized: Boolean,
    canResize: Boolean,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp,
) {
    if (!canResize || window == null) return
    if (maximized) return
    Box(modifier.fillMaxSize()) {
        // Edges first, then corners -- corners win where they overlap an edge.
        Grip(WindowEdge.N, Alignment.TopCenter, window, host, thickness)
        Grip(WindowEdge.S, Alignment.BottomCenter, window, host, thickness)
        Grip(WindowEdge.W, Alignment.CenterStart, window, host, thickness)
        Grip(WindowEdge.E, Alignment.CenterEnd, window, host, thickness)
        Grip(WindowEdge.NW, Alignment.TopStart, window, host, thickness)
        Grip(WindowEdge.NE, Alignment.TopEnd, window, host, thickness)
        Grip(WindowEdge.SW, Alignment.BottomStart, window, host, thickness)
        Grip(WindowEdge.SE, Alignment.BottomEnd, window, host, thickness)
    }
}

@Composable
private fun BoxScope.Grip(
    edge: WindowEdge,
    align: Alignment,
    window: ComposeWindow,
    host: WindowHost,
    thickness: Dp,
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
                        if (host.beginResize(edge)) continue
                        val startMouse = MouseInfo.getPointerInfo()?.location ?: continue
                        val start = window.bounds
                        val min = window.minimumSize
                        drag(down.id) { change ->
                            change.consume()
                            val now = MouseInfo.getPointerInfo()?.location ?: return@drag
                            window.bounds = resizedBounds(start, edge, now.x - startMouse.x, now.y - startMouse.y, min.width, min.height)
                        }
                    }
                }
            },
    )
}
