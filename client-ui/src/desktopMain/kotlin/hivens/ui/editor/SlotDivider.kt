package hivens.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.theme.NxTheme
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import java.awt.Cursor

// Phase G4 drag-divider. Sits between two adjacent widgets in a Row/Column
// slot in edit mode; dragging redistributes weight between the two
// neighbors while preserving their combined main-axis size.
//
// The math captures both neighbors' px at drag start as a FIXED reference,
// then maps the accumulated drag delta onto that reference -- so the live
// setWidgetWeight writes (which change the layout mid-drag) never feed back
// into the calculation and run away. Weights become px-magnitude values and
// stay mutually consistent as the user drags the slot's other dividers.
//
// Grid slots size by column count, not dividers -> no-op there.
@Composable
internal fun SlotDivider(
    path: SlotPath,
    content: SlotContent,
    leftIndex: Int,
    controller: EditModeController,
    registry: DropTargetRegistry,
) {
    if (content.orientation == SlotOrientation.Grid) return
    val widgets = content.widgets
    if (leftIndex < 0 || leftIndex >= widgets.lastIndex) return
    val left  = widgets[leftIndex]
    val right = widgets[leftIndex + 1]
    val isRow = content.orientation == SlotOrientation.Row

    val minPx = with(LocalDensity.current) { 40.dp.toPx() }
    var startLeftPx by remember(left.instanceId, right.instanceId) { mutableStateOf(0f) }
    var sumPx       by remember(left.instanceId, right.instanceId) { mutableStateOf(0f) }
    var accum       by remember(left.instanceId, right.instanceId) { mutableStateOf(0f) }

    val cursor = remember(isRow) {
        PointerIcon(Cursor(if (isRow) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    }

    val drag = Modifier.pointerInput(left.instanceId, right.instanceId, isRow) {
        detectDragGestures(
            onDragStart = {
                val lr = registry.widgetRect(path, left.instanceId)
                val rr = registry.widgetRect(path, right.instanceId)
                val lLen = if (isRow) (lr?.width ?: 0f) else (lr?.height ?: 0f)
                val rLen = if (isRow) (rr?.width ?: 0f) else (rr?.height ?: 0f)
                startLeftPx = lLen
                sumPx       = lLen + rLen
                accum       = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (sumPx > 0f) {
                    accum += if (isRow) dragAmount.x else dragAmount.y
                    val newLeft = dividerLeftWeight(startLeftPx, sumPx, accum, minPx)
                    controller.setWidgetWeight(path, left.instanceId, newLeft)
                    controller.setWidgetWeight(path, right.instanceId, sumPx - newLeft)
                }
            },
        )
    }

    val bar = NxTheme.colors.primary.copy(alpha = 0.55f)
    if (isRow) {
        Box(
            modifier         = Modifier.fillMaxHeight().width(10.dp).pointerHoverIcon(cursor).then(drag),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().width(2.dp).background(bar))
        }
    } else {
        Box(
            modifier         = Modifier.fillMaxWidth().height(10.dp).pointerHoverIcon(cursor).then(drag),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(bar))
        }
    }
}

// New left-neighbor weight (px units) for a divider drag: the start px plus
// the accumulated delta, clamped so neither side drops below minPx. The
// right neighbor takes sumPx - this. Pure so the resize math is testable
// without driving a real pointer gesture.
internal fun dividerLeftWeight(startLeftPx: Float, sumPx: Float, accum: Float, minPx: Float): Float {
    val upper = (sumPx - minPx).coerceAtLeast(minPx)
    return (startLeftPx + accum).coerceIn(minPx.coerceAtMost(upper), upper)
}
