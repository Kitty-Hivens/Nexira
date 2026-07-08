package hivens.ui.nx

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Cursor

// The pointer states, taken from the OS/DE cursor theme (predefined AWT cursors map
// to the platform's own pointers). Never a bundled glyph: a custom cursor reads as
// alien on a platform whose users expect their native pointer (Rule 7).
private val GrabCursor     = PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
private val GrabbingCursor = PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))

/**
 * The move-drag affordance the library owns: it drives the pointer from drag
 * availability and runs the drag gesture, so no screen sets the cursor by hand
 * (Rule 0/5). Attach it to a drag handle or a movable element.
 *
 * [enabled] false means the element cannot move right now: the gesture is inert and
 * the pointer stays the platform default arrow -- "not grabbable" reads as the
 * absence of the grab hand, the browser-native way, with no not-allowed glyph (AWT
 * has no native one, and a bundled bitmap would not match the OS theme). [enabled]
 * true shows the native grab hand on hover and the native move cursor while dragging.
 *
 * [onDrag] receives the frame delta in pixels; the gesture math (snapping, bounds)
 * stays at the call site -- this owns only the state->cursor mapping and the gesture
 * plumbing, not what a drag means.
 */
fun Modifier.nxDraggable(
    enabled: Boolean,
    onDragStart: (Offset) -> Unit = {},
    onDrag: (dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit = {},
): Modifier = composed {
    var dragging by remember { mutableStateOf(false) }
    val cursor = when {
        !enabled -> PointerIcon.Default
        dragging -> GrabbingCursor
        else     -> GrabCursor
    }
    pointerHoverIcon(cursor).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectDragGestures(
            onDragStart  = { dragging = true; onDragStart(it) },
            onDragEnd    = { dragging = false; onDragEnd() },
            onDragCancel = { dragging = false; onDragEnd() },
            onDrag       = { change, amount -> change.consume(); onDrag(amount) },
        )
    }
}
