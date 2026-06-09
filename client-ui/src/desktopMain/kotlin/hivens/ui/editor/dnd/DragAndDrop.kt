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
import hivens.widget.model.SlotOrientation
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

    fun registerWidget(path: SlotPath, instanceId: String, index: Int, rect: Rect) {
        val byId = widgets.getOrPut(path) { mutableStateMapOf() }
        byId[instanceId] = WidgetBounds(index, rect)
    }

    fun unregisterWidget(path: SlotPath, instanceId: String) {
        widgets[path]?.remove(instanceId)
    }

    // Window-coord rect of one registered widget. Used by the Phase G
    // slot dividers to read the two neighbors' main-axis px at drag start.
    fun widgetRect(path: SlotPath, instanceId: String): Rect? =
        widgets[path]?.get(instanceId)?.rect

    // Window-coord top-left of a registered slot (Canvas slots report bounds via
    // LocalSlotBoundsReporter). Lets a palette drop land at the release point.
    // Null when the slot has not reported bounds.
    fun slotOrigin(path: SlotPath): Offset? = slotBounds[path]?.topLeft

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
            // Tie-break by nested depth so two overlapping rects of
            // identical area resolve deterministically -- without this
            // the winner depends on SnapshotStateMap iteration order
            // (unspecified), and a hit-test that flickers between two
            // candidates would feel like the editor is jumping.
            val current = best
            val currentDepth = current?.nested?.size ?: -1
            if (area < bestArea || (area == bestArea && path.nested.size > currentDepth)) {
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
    // [0, count] -- count means "append at end". Finds the widget whose
    // main-axis midpoint the pointer is before; inserts at that widget's
    // position. Main axis = X for Row slots, Y for Column/Grid. If the
    // pointer is past all widgets, append.
    fun insertionIndexInSlot(
        path: SlotPath,
        pointInWindow: Offset,
        orientation: SlotOrientation = SlotOrientation.Column,
    ): Int {
        val items = widgets[path]?.values?.sortedBy { it.index } ?: return 0
        if (items.isEmpty()) return 0
        if (orientation == SlotOrientation.Grid) {
            // Row-major: insert before the first cell the pointer sits above
            // (an earlier row) or, within the same row band, left of center.
            items.forEach { wb ->
                val r = wb.rect
                if (pointInWindow.y < r.top) return wb.index
                if (pointInWindow.y <= r.bottom && pointInWindow.x < r.left + r.width / 2f) return wb.index
            }
            return items.size
        }
        val horizontal = orientation == SlotOrientation.Row
        items.forEach { wb ->
            val mid = if (horizontal) wb.rect.left + wb.rect.width / 2f
                      else wb.rect.top + wb.rect.height / 2f
            val coord = if (horizontal) pointInWindow.x else pointInWindow.y
            if (coord < mid) return wb.index
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

@Suppress("unused") private fun touchDerivedStateOf() = derivedStateOf { 0 }
@Suppress("unused") private fun touchSnapshot() = Snapshot.current
