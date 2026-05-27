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
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

// ── Drag payload + active state ─────────────────────────────────────────────

sealed class DragPayload {
    data class ExistingWidget(
        val source: SlotPath,
        val sourceIndex: Int,
        val instance: WidgetInstance,
    ) : DragPayload()

    // Palette-originated drag. Resolves to an EditModeController.addWidget
    // at drop time; the target SlotPath comes from the registry hit-test.
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

data class WidgetBounds(val index: Int, val rect: Rect)

// Hierarchical drop targets. Keys are SlotPath (full address through
// nested containers), so a drop into "container body" on home.new is
// distinct from a drop into the same slot id on the right rail.
//
// slotForPoint returns the INNERMOST slot whose rect (widget rect,
// vertical span fallback, or empty-slot placeholder rect) contains the
// pointer -- "innermost" = smallest area. Without that ordering, a
// container's outer rect would always win over its own children and
// the user could never drop into a nested slot.
class DropTargetRegistry {
    private val widgets: SnapshotStateMap<SlotPath, SnapshotStateMap<String, WidgetBounds>> =
        mutableStateMapOf()
    private val slotBounds: SnapshotStateMap<SlotPath, Rect> = mutableStateMapOf()

    fun registerSlot(path: SlotPath, rect: Rect) {
        slotBounds[path] = rect
    }

    fun unregisterSlot(path: SlotPath) {
        slotBounds.remove(path)
        widgets.remove(path)
    }

    fun registerWidget(path: SlotPath, instanceId: String, index: Int, rect: Rect) {
        val byId = widgets.getOrPut(path) { mutableStateMapOf() }
        byId[instanceId] = WidgetBounds(index, rect)
    }

    fun unregisterWidget(path: SlotPath, instanceId: String) {
        widgets[path]?.remove(instanceId)
    }

    // Two passes:
    //   1) exact rect hit across all registered sources (widget rects +
    //      empty-slot placeholder bounds), innermost (smallest area)
    //      wins. Both kinds compete in the same pass so a nested empty
    //      slot beats its enclosing container's widget rect.
    //   2) vertical-span-with-12px-tolerance fallback for "in-between"
    //      gap drops where the pointer is in a slot's vertical bracket
    //      but not over any of its widgets. Only consulted when pass 1
    //      finds no exact hit.
    // Returns null when the pointer is outside every registered slot.
    fun slotForPoint(pointInWindow: Offset): SlotPath? {
        var best: SlotPath? = null
        var bestArea = Float.POSITIVE_INFINITY

        fun consider(rect: Rect, path: SlotPath) {
            if (!rect.contains(pointInWindow)) return
            val area = rect.width * rect.height
            if (area < bestArea) {
                best = path
                bestArea = area
            }
        }

        // Pass 1: every exact rect competes by area. Crucially, the
        // empty-slot placeholder rect of a container's child slot must
        // be eligible against the container widget's own rect -- the
        // child slot is strictly smaller and the user means to drop
        // INTO it.
        widgets.forEach { (path, byId) ->
            byId.values.forEach { wb -> consider(wb.rect, path) }
        }
        slotBounds.forEach { (path, rect) -> consider(rect, path) }
        if (best != null) return best

        // Pass 2: vertical-span fallback. Per-slot virtual bounding
        // rect across all that slot's widgets, with horizontal extent
        // matching widest widget and vertical padding for gap drops.
        val tolerance = 12f
        widgets.forEach { (path, byId) ->
            val items = byId.values
            if (items.isEmpty()) return@forEach
            val minY = items.minOf { it.rect.top } - tolerance
            val maxY = items.maxOf { it.rect.bottom } + tolerance
            val minX = items.minOf { it.rect.left }
            val maxX = items.maxOf { it.rect.right }
            consider(Rect(minX, minY, maxX, maxY), path)
        }
        return best
    }

    // Insertion index for a pointer inside a known slot. Index is in
    // [0, count] -- count means "append at end". Algorithm: find the
    // widget whose vertical midpoint the pointer is above; insert at
    // that widget's position. If pointer is below all widgets, append.
    fun insertionIndexInSlot(path: SlotPath, pointInWindow: Offset): Int {
        val items = widgets[path]?.values?.sortedBy { it.index } ?: return 0
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
// widgetBoundsProvider returns the widget's window-coord bounds so the
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
        val pickup = drag.position - Offset(0f, 0f)
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

fun Modifier.slotBounds(
    registry: DropTargetRegistry,
    path: SlotPath,
): Modifier = this.onGloballyPositioned { coords: LayoutCoordinates ->
    registry.registerSlot(path, coords.boundsInWindow())
}

fun Modifier.widgetBounds(
    registry: DropTargetRegistry,
    path: SlotPath,
    instanceId: String,
    index: Int,
): Modifier = this.onGloballyPositioned { coords: LayoutCoordinates ->
    registry.registerWidget(path, instanceId, index, coords.boundsInWindow())
}

// SlotAddress-keyed compat for callers that have not migrated yet.
// Internally promotes to a root-level SlotPath -- safe for flat
// surfaces, lossy for nested ones.
@Deprecated(
    message     = "Use the SlotPath form; SlotAddress loses nested context",
    replaceWith = ReplaceWith("slotBounds(registry, SlotPath(slot.surface, slot.slot))"),
)
fun Modifier.slotBounds(registry: DropTargetRegistry, slot: SlotAddress): Modifier =
    slotBounds(registry, SlotPath(slot.surface, slot.slot))

@Suppress("unused") private fun touchDerivedStateOf() = derivedStateOf { 0 }
@Suppress("unused") private fun touchSnapshot() = Snapshot.current
