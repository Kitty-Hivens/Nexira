package hivens.ui.chrome

import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Cursor

/**
 * Edge/corner resize grips for the undecorated window -- undecorated drops the
 * native resize border, so we synthesize 8 thin hit-zones that mutate
 * [WindowState.size] / [WindowState.position]. Clamped to [minSize]; only shown
 * while Floating (a maximized window is not resizable).
 *
 * Best-effort under Wayland/XWayland: AWT setBounds is honored but the compositor
 * owns the surface, so a fast drag can lag. Overlay this LAST so the grips sit
 * above content; the 6dp band is small enough not to steal edge clicks.
 */
@Composable
fun WindowResizeHandles(
    state: WindowState,
    minSize: DpSize,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp,
) {
    // On a tiling WM the compositor owns sizing (and may fullscreen the surface
    // without ever setting placement=Maximized) -- client-side grips would let
    // the user drag-resize a tiled/fullscreen window. Leave resizing to the WM.
    if (IS_TILING_WM) return
    if (state.placement != WindowPlacement.Floating) return
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        // Edges first, then corners -- corners win where they overlap an edge.
        Grip(ResizeEdge.N, Alignment.TopCenter, state, minSize, density, thickness)
        Grip(ResizeEdge.S, Alignment.BottomCenter, state, minSize, density, thickness)
        Grip(ResizeEdge.W, Alignment.CenterStart, state, minSize, density, thickness)
        Grip(ResizeEdge.E, Alignment.CenterEnd, state, minSize, density, thickness)
        Grip(ResizeEdge.NW, Alignment.TopStart, state, minSize, density, thickness)
        Grip(ResizeEdge.NE, Alignment.TopEnd, state, minSize, density, thickness)
        Grip(ResizeEdge.SW, Alignment.BottomStart, state, minSize, density, thickness)
        Grip(ResizeEdge.SE, Alignment.BottomEnd, state, minSize, density, thickness)
    }
}

private enum class ResizeEdge(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
    val cursor: Int,
) {
    W(true, false, false, false, Cursor.W_RESIZE_CURSOR),
    E(false, false, true, false, Cursor.E_RESIZE_CURSOR),
    N(false, true, false, false, Cursor.N_RESIZE_CURSOR),
    S(false, false, false, true, Cursor.S_RESIZE_CURSOR),
    NW(true, true, false, false, Cursor.NW_RESIZE_CURSOR),
    NE(false, true, true, false, Cursor.NE_RESIZE_CURSOR),
    SW(true, false, false, true, Cursor.SW_RESIZE_CURSOR),
    SE(false, false, true, true, Cursor.SE_RESIZE_CURSOR);

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

@Composable
private fun BoxScope.Grip(
    edge: ResizeEdge,
    align: Alignment,
    state: WindowState,
    minSize: DpSize,
    density: Density,
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
            .pointerInput(edge) {
                detectDragGestures { change, drag ->
                    change.consume()
                    applyResize(edge, drag, state, minSize, density)
                }
            },
    )
}

private fun applyResize(edge: ResizeEdge, drag: Offset, state: WindowState, minSize: DpSize, density: Density) {
    val dx = with(density) { drag.x.toDp() }
    val dy = with(density) { drag.y.toDp() }
    var w = state.size.width
    var h = state.size.height
    val absPos = state.position as? WindowPosition.Absolute
    val canMove = absPos != null
    var x = absPos?.x ?: 0.dp
    var y = absPos?.y ?: 0.dp

    if (edge.right) w += dx
    if (edge.bottom) h += dy
    if (edge.left) { w -= dx; if (canMove) x += dx }
    if (edge.top) { h -= dy; if (canMove) y += dy }

    if (w < minSize.width) {
        if (edge.left && canMove) x -= (minSize.width - w)
        w = minSize.width
    }
    if (h < minSize.height) {
        if (edge.top && canMove) y -= (minSize.height - h)
        h = minSize.height
    }

    state.size = DpSize(w, h)
    if ((edge.left || edge.top) && canMove) state.position = WindowPosition(x, y)
}
