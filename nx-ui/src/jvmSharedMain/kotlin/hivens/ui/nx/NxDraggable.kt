package hivens.ui.nx

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import hivens.ui.cursor.dragCursor

/**
 * The move-drag affordance the library owns: it drives the pointer from drag
 * availability and runs the drag gesture, so no screen sets the cursor by hand
 * (Rule 0/5). Attach it to a drag handle or a movable element.
 *
 * [enabled] false means the element cannot move right now: the gesture is inert and
 * the pointer is the look's for what would be dragged and cannot be. [enabled] true
 * shows the look's pointer for something to drag, and its pointer for dragging while
 * it is.
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
    dragCursor(dragging, enabled).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectDragGestures(
            onDragStart  = { dragging = true; onDragStart(it) },
            onDragEnd    = { dragging = false; onDragEnd() },
            onDragCancel = { dragging = false; onDragEnd() },
            onDrag       = { change, amount -> change.consume(); onDrag(amount) },
        )
    }
}
