package hivens.ui.editor.dnd

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import hivens.widget.model.FlowSpec
import hivens.widget.model.NodePath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

// ── Drag payload + active state ─────────────────────────────────────────────

sealed class DragPayload {
    data class ExistingWidget(
        val source: NodePath,
        val sourceIndex: Int,
        val instance: WidgetInstance,
    ) : DragPayload()

    // A drag out of the palette. It becomes an add when dropped, into the slot the registry finds under the pointer.
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

// ── Which widget is meant ───────────────────────────────────────────────────

/** One widget where it stands: a name is unique within a composition, a path says where it is drawn now. */
data class WidgetKey(val path: NodePath, val instanceId: String)

/**
 * Whether a press is [key]'s to take, [named] being the widget the registry names under the pointer.
 *
 * Every chrome under a press that reaches several layers sees it, and the one the registry names, the one the hover
 * outline is drawn on, takes it. Widgets in one slot do not share a press, so where the named one is another widget of
 * this slot, or inside one, it cannot hear the press and the uppermost keeps it. Inside this widget the named one has
 * had the press first.
 */
fun pressBelongsTo(key: WidgetKey, named: WidgetKey?): Boolean {
    if (named == null || named == key) return true
    val mine = key.path
    val there = named.path
    val depth = mine.nested.size
    val inMySlot = there.composition == mine.composition && there.root == mine.root &&
        there.nested.size >= depth && there.nested.subList(0, depth) == mine.nested
    if (!inMySlot) return false
    return there.nested.size == depth || there.nested[depth].parentInstanceId != key.instanceId
}

/**
 * The widget the pointer is over and the one a finger last pressed, each one widget for the whole editor.
 *
 * Asked of every widget's own hover, a container and the widget inside it were both hovered at once, and the
 * container's handles, drawn above the inner one's, took the press where their edges met. One answer for the editor,
 * the innermost widget under the pointer, gives each place one owner. A finger cannot hover, so the widget it last
 * pressed keeps its handles until another is pressed.
 */
@Stable
class EditFocus {
    var hovered: WidgetKey? by mutableStateOf(null)
    var touched: WidgetKey? by mutableStateOf(null)

    /** Whether [key] shows its handles now. */
    fun shows(key: WidgetKey): Boolean = hovered == key || touched == key
}

// ── Drop target registry ────────────────────────────────────────────────────

data class WidgetBounds(val index: Int, val rect: Rect)

// Where every slot and widget the editor decorates is drawn, keyed by NodePath, the full address through nested
// containers, so a drop into a container's "body" is told from the same slot of another.
//
// The slot a point is over is answered from the slots alone: every slot reports its own rect, so a gap in a flow is
// inside its slot and a container's rect never stands in for the slot it holds. The innermost, smallest by area, wins.
// Widget rects answer where in its slot a drop goes and who a moving widget lands on, and which widget is under the
// pointer.
class DropTargetRegistry {
    private val widgets: SnapshotStateMap<NodePath, SnapshotStateMap<String, WidgetBounds>> =
        mutableStateMapOf()
    private val slotBounds: SnapshotStateMap<NodePath, Rect> = mutableStateMapOf()
    // The whole placement box where it is larger than what is on screen, which is
    // a slot that scrolls. Absent means the two are the same rect.
    private val slotContent: SnapshotStateMap<NodePath, Rect> = mutableStateMapOf()

    /**
     * Which widgets the one being moved is currently sitting on top of.
     *
     * Free placement has no collision rule and is not getting one: refusing a
     * position mid-gesture would mean clearing a path before you could put
     * anything anywhere, which on a full surface is half the screen moved by
     * hand. What it gets instead is the answer to the question the editor could
     * not answer at all, which is WHERE the overlap is. Published by whichever
     * widget is being dragged or resized and read by everybody, so the ones being
     * landed on say so while the gesture is still live and the drop is still the
     * person's to make.
     */
    var overlapped: Set<WidgetKey> by mutableStateOf(emptySet())
        private set

    fun publishOverlap(keys: Set<WidgetKey>) {
        if (overlapped != keys) overlapped = keys
    }

    /**
     * Widgets in [path] whose rect meets [rect], leaving out [movingId] itself.
     *
     * Touching edges do not count. A widget laid flush against its neighbour is
     * the arrangement somebody wanted, and reporting it as a collision would
     * leave the warning colour on half the surface.
     */
    fun overlapping(path: NodePath, rect: Rect, movingId: String): Set<WidgetKey> {
        val byId = widgets[path] ?: return emptySet()
        return byId.entries
            .filter { (id, bounds) -> id != movingId && bounds.rect.overlapsStrictly(rect) }
            .mapTo(mutableSetOf()) { WidgetKey(path, it.key) }
    }

    /** Where a slot is on screen, which is what a pointer can be over. */
    fun registerSlot(path: NodePath, rect: Rect) {
        slotBounds[path] = rect
    }

    /**
     * The whole of a placement slot, most of it off screen in a slot that scrolls, which is what a point inside it
     * converts against.
     */
    fun registerSlotContent(path: NodePath, content: Rect) {
        if (content == slotBounds[path]) slotContent.remove(path) else slotContent[path] = content
    }

    /**
     * The slot that reported under [path] has left the composition. Kept, its rectangle
     * went on answering the hit-test after its screen had gone, and since the
     * smallest rectangle wins, a stale one beat the live pane beneath it.
     */
    fun withdrawSlot(path: NodePath) {
        slotBounds.remove(path)
        slotContent.remove(path)
    }

    fun registerWidget(path: NodePath, instanceId: String, index: Int, rect: Rect) {
        val byId = widgets.getOrPut(path) { mutableStateMapOf() }
        byId[instanceId] = WidgetBounds(index, rect)
    }

    fun unregisterWidget(path: NodePath, instanceId: String) {
        widgets[path]?.remove(instanceId)
    }

    /** The part of a slot that is on screen. */
    fun slotRect(path: NodePath): Rect? = slotBounds[path]

    /**
     * The whole of a slot, on screen or not, for a drop that converts the pointer
     * into the slot's own coordinates. On a page scrolled down by a screen this
     * starts a screen above the window, which is where the page's origin is.
     */
    fun slotContentRect(path: NodePath): Rect? = slotContent[path] ?: slotRect(path)

    /**
     * The innermost slot under [pointInWindow], smallest by area, ties to the deeper path so the answer does not
     * flicker between two of one size. Null outside every slot.
     */
    fun slotForPoint(pointInWindow: Offset): NodePath? = innermost(slotBounds.entries.map { it.key to it.value }, pointInWindow) { it.nested.size }

    /** The innermost widget under [pointInWindow], or null where there is none. */
    fun widgetForPoint(pointInWindow: Offset): WidgetKey? =
        innermost(widgets.flatMap { (path, byId) -> byId.map { (id, b) -> WidgetKey(path, id) to b.rect } }, pointInWindow) { it.path.nested.size }

    private fun <K> innermost(candidates: List<Pair<K, Rect>>, point: Offset, depth: (K) -> Int): K? {
        var best: K? = null
        var bestArea = Float.POSITIVE_INFINITY
        for ((key, rect) in candidates) {
            if (!rect.contains(point)) continue
            val area = rect.width * rect.height
            val current = best
            if (area < bestArea || (area == bestArea && current != null && depth(key) > depth(current))) {
                best = key
                bestArea = area
            }
        }
        return best
    }

    // Insertion index for a pointer inside a known slot. Index is in
    // [0, count] -- count means "append at end". Finds the widget whose
    // main-axis midpoint the pointer is before; inserts at that widget's
    // position. Main axis = X for a horizontal flow, Y for a vertical one. If
    // the pointer is past all widgets, append.
    //
    // A placement slot has no insertion point at all: order there is paint
    // order, not position, so a drop appends and the placement decides where it
    // lands. Answering anything else would move a widget the user did not touch.
    // No default for [flow]: it decides between "insert here" and "append", and a
    // forgotten argument would move a widget the user never touched.
    fun insertionIndexInSlot(
        path: NodePath,
        pointInWindow: Offset,
        flow: FlowSpec?,
    ): Int {
        val items = widgets[path]?.values?.sortedBy { it.index } ?: return 0
        if (items.isEmpty()) return 0
        if (flow == null) return items.size
        if (flow.wrap > 0) {
            // Reading order along the flow's own axis: before the first cell that
            // sits on a later line, or, within the same line, before its middle.
            // A vertical wrap stacks its lines side by side, so the two axes swap.
            val acrossLines = if (flow.horizontal) pointInWindow.y else pointInWindow.x
            val alongLine = if (flow.horizontal) pointInWindow.x else pointInWindow.y
            items.forEach { wb ->
                val r = wb.rect
                val lineStart = if (flow.horizontal) r.top else r.left
                val lineEnd = if (flow.horizontal) r.bottom else r.right
                val middle = if (flow.horizontal) r.left + r.width / 2f else r.top + r.height / 2f
                if (acrossLines < lineStart) return wb.index
                if (acrossLines <= lineEnd && alongLine < middle) return wb.index
            }
            return items.size
        }
        val horizontal = flow.horizontal
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

val LocalEditFocus: ProvidableCompositionLocal<EditFocus> =
    staticCompositionLocalOf { error("LocalEditFocus not provided -- mount EditorSurfaceHost") }

// ── Drag-source modifier ────────────────────────────────────────────────────

// Attach to the drag handle, not the whole widget -- prevents accidental
// drag of an interactive control (button, slider). The lambda
// widgetBoundsProvider returns the widget's window-coord bounds so the
// ghost can position itself at the right pickup offset. A drag cut off
// before it was let go drops nothing.
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
        // Held where it was pressed. Where the pointer crossed the slop is a whole jump further on after a fast
        // first move, and the ghost then trailed the pointer by that jump for the rest of the drag.
        controller.begin(
            payload         = payload,
            pointerInWindow = bounds.topLeft + drag.position,
            pickupOffset    = down.position,
            widgetSize      = Offset(bounds.width, bounds.height),
            ghost           = ghost,
        )
        // Accumulated, for the same reason the chrome's flow drag accumulates: the
        // source's bounds move while the drag is in flight, and a pointer measured
        // against them reports the source's movement as the user's.
        var lastPointer = bounds.topLeft + drag.position
        // finally: a drag cut off with the palette closing under it, Esc or Ctrl+E
        // mid-gesture, ran nothing after this, and the ghost and the hidden cursor
        // it draws stayed up over the whole window. A cut-off drag drops nothing.
        try {
            val released = drag(drag.id) { change: PointerInputChange ->
                lastPointer += change.positionChange()
                controller.update(lastPointer)
                change.consume()
            }
            if (released) onDragEnd(lastPointer)
        } finally {
            controller.end()
        }
    }
}

/**
 * Overlap with a shared edge left out.
 *
 * `Rect.overlaps` counts a touch as an intersection, which for two widgets laid
 * flush is the arrangement rather than a fault.
 */
private fun Rect.overlapsStrictly(other: Rect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom
