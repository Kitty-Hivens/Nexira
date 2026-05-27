package hivens.ui.editor.dnd

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import hivens.widget.model.SlotAddress
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

// ── Drag payload + active state ─────────────────────────────────────────────

sealed class DragPayload {
    data class ExistingWidget(
        val source: SlotAddress,
        val sourceIndex: Int,
        val instance: WidgetInstance,
    ) : DragPayload()

    // Palette drag arrives in editor-3. ExistingWidget covers editor-2.
    data class PaletteWidget(val kind: WidgetKind) : DragPayload()
}

data class ActiveDrag(
    val payload: DragPayload,
    val pointerInWindow: Offset,
    val pickupOffset: Offset,
    val widgetSize: Offset,
    val ghost: @Composable () -> Unit,
)

// ── Drag controller ─────────────────────────────────────────────────────────

class DragController {
    private val _active = mutableStateOf<ActiveDrag?>(null)
    val active: ActiveDrag? get() = _active.value
    val isDragging: Boolean get() = _active.value != null

    fun begin(
        payload: DragPayload,
        pointerInWindow: Offset,
        pickupOffset: Offset,
        widgetSize: Offset,
        ghost: @Composable () -> Unit,
    ) {
        _active.value = ActiveDrag(
            payload         = payload,
            pointerInWindow = pointerInWindow,
            pickupOffset    = pickupOffset,
            widgetSize      = widgetSize,
            ghost           = ghost,
        )
    }

    fun update(pointerInWindow: Offset) {
        _active.value = _active.value?.copy(pointerInWindow = pointerInWindow)
    }

    fun end(): DragPayload? {
        val payload = _active.value?.payload
        _active.value = null
        return payload
    }
}

// ── Drop target registry ────────────────────────────────────────────────────

// Per-widget bounds in window coords. Keyed by SlotAddress then by
// position-in-slot (matches LayoutGraph ordering). Empty slots are
// registered with an empty rect list so the registry knows the slot
// exists but has no widgets to hit-test against.
data class WidgetBounds(val index: Int, val rect: Rect)

class DropTargetRegistry {
    private val widgets: SnapshotStateMap<SlotAddress, SnapshotStateMap<String, WidgetBounds>> =
        mutableStateMapOf()
    private val slotBounds: SnapshotStateMap<SlotAddress, Rect> = mutableStateMapOf()

    fun registerSlot(slot: SlotAddress, rect: Rect) {
        slotBounds[slot] = rect
    }

    fun unregisterSlot(slot: SlotAddress) {
        slotBounds.remove(slot)
        widgets.remove(slot)
    }

    fun registerWidget(slot: SlotAddress, instanceId: String, index: Int, rect: Rect) {
        val byId = widgets.getOrPut(slot) { mutableStateMapOf() }
        byId[instanceId] = WidgetBounds(index, rect)
    }

    fun unregisterWidget(slot: SlotAddress, instanceId: String) {
        widgets[slot]?.remove(instanceId)
    }

    // Locates which slot the pointer is currently over. Three passes:
    // 1) exact rect hit on any widget, 2) the slot whose tracked
    // widget rects most-nearly bracket the pointer Y, 3) empty-slot
    // bounds registered by EmptySlotDecorator. Returns null when the
    // pointer is outside every registered slot.
    fun slotForPoint(pointInWindow: Offset): SlotAddress? {
        widgets.forEach { (slot, byId) ->
            byId.values.forEach { wb ->
                if (wb.rect.contains(pointInWindow)) return slot
            }
        }
        // Fallback: pick the slot whose vertical span covers the
        // pointer, plus a 12px tolerance to make gap drops feel
        // forgiving. Horizontal span must also cover the pointer.
        val tolerance = 12f
        widgets.forEach { (slot, byId) ->
            val items = byId.values
            if (items.isEmpty()) return@forEach
            val minY = items.minOf { it.rect.top } - tolerance
            val maxY = items.maxOf { it.rect.bottom } + tolerance
            val minX = items.minOf { it.rect.left }
            val maxX = items.maxOf { it.rect.right }
            if (pointInWindow.y in minY..maxY && pointInWindow.x in minX..maxX) {
                return slot
            }
        }
        // Empty slot fallback: EmptySlotDecorator registers slot
        // bounds; drop into the empty placeholder lands here.
        slotBounds.forEach { (slot, rect) ->
            if (rect.contains(pointInWindow)) return slot
        }
        return null
    }

    // Insertion index for a pointer inside a known slot. Index is in
    // [0, count] -- count means "append at end". Algorithm: find the
    // widget whose vertical midpoint the pointer is above; insert at
    // that widget's position. If pointer is below all widgets, append.
    fun insertionIndexInSlot(slot: SlotAddress, pointInWindow: Offset): Int {
        val items = widgets[slot]?.values?.sortedBy { it.index } ?: return 0
        if (items.isEmpty()) return 0
        items.forEach { wb ->
            val midY = wb.rect.top + wb.rect.height / 2f
            if (pointInWindow.y < midY) return wb.index
        }
        return items.size
    }
}

// ── Composition locals ─────────────────────────────────────────────────────

val LocalDragController: ProvidableCompositionLocal<DragController> =
    staticCompositionLocalOf { error("LocalDragController not provided -- mount EditorSurfaceHost") }

val LocalDropTargetRegistry: ProvidableCompositionLocal<DropTargetRegistry> =
    staticCompositionLocalOf { error("LocalDropTargetRegistry not provided -- mount EditorSurfaceHost") }

// ── Drag-source modifier ────────────────────────────────────────────────────

// Attach to the drag handle, not the whole widget -- prevents accidental
// drag of an interactive control (button, slider). The lambda
// `widgetBoundsProvider` returns the widget's window-coord bounds so the
// ghost can position itself at the right pickup offset.
fun Modifier.dragSource(
    controller: DragController,
    payload: DragPayload,
    widgetBoundsProvider: () -> Rect?,
    ghost: @Composable () -> Unit,
    onDragEnd: (committedPointer: Offset) -> Unit,
): Modifier = this.pointerInput(payload) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        val bounds = widgetBoundsProvider() ?: return@awaitEachGesture
        val pickup = drag.position - Offset(0f, 0f)  // pointer offset within source
        controller.begin(
            payload         = payload,
            pointerInWindow = bounds.topLeft + drag.position,
            pickupOffset    = pickup,
            widgetSize      = Offset(bounds.width, bounds.height),
            ghost           = ghost,
        )
        var lastPointer = bounds.topLeft + drag.position
        drag(drag.id) { change: PointerInputChange ->
            val widgetBounds = widgetBoundsProvider()
            if (widgetBounds != null) {
                lastPointer = widgetBounds.topLeft + change.position
                controller.update(lastPointer)
            }
            change.consume()
        }
        onDragEnd(lastPointer)
        controller.end()
    }
}

// ── Drop-target modifier ────────────────────────────────────────────────────

// Registers the slot's bounds with the registry. Per-widget bounds are
// registered separately by EditableWidgetChrome via widgetBounds().
fun Modifier.slotBounds(
    registry: DropTargetRegistry,
    slot: SlotAddress,
): Modifier = this.onGloballyPositioned { coords: LayoutCoordinates ->
    registry.registerSlot(slot, coords.boundsInWindow())
}

fun Modifier.widgetBounds(
    registry: DropTargetRegistry,
    slot: SlotAddress,
    instanceId: String,
    index: Int,
): Modifier = this.onGloballyPositioned { coords: LayoutCoordinates ->
    registry.registerWidget(slot, instanceId, index, coords.boundsInWindow())
}

// derivedStateOf import suppression -- present for future shape stability
@Suppress("unused") private fun touchDerivedStateOf() = derivedStateOf { 0 }
@Suppress("unused") private fun touchSnapshot() = Snapshot.current
