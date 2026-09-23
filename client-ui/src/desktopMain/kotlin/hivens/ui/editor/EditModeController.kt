package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.model.FlowSpec
import hivens.widget.model.GRID_MAX
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceInsets
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.insertWidget
import hivens.widget.model.moveWidget
import hivens.widget.model.placeWidgetInGrid
import hivens.widget.model.removeWidget
import hivens.widget.model.reorderInSlot
import hivens.widget.model.resizeWidgetInGrid
import hivens.widget.model.setFlow
import hivens.widget.model.setGrid
import hivens.widget.model.setSlotAdaptive
import hivens.widget.model.setWidgetAnchor
import hivens.widget.model.setWidgetBounds
import hivens.widget.model.setWidgetOffset
import hivens.widget.model.setWidgetPadding
import hivens.widget.model.setWidgetSize
import hivens.widget.model.setWidgetZ
import hivens.widget.model.traverse
import hivens.widget.model.updateWidgetSurface
import hivens.widget.model.updateWidgetProps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

// Single mutation entry point for the editor. Every operation resolves
// to one LayoutGraphRepository.update { transform } call -- the repo
// already handles atomic write + StateFlow re-emission, so the
// controller is just an ergonomic facade.
//
// Methods fire-and-forget on the supplied scope. The repo's StateFlow
// drives recomposition; the caller never awaits the write.
//
// SlotPath is the canonical form. Each call corresponds to one
// LayoutGraph transform applied at the path's leaf SlotContent.
class EditModeController(
    private val repo: LayoutGraphRepository,
    private val scope: CoroutineScope,
) {
    // Editor mutations run on a single-thread view of Default so per-frame
    // canvas writes (offset / size during a drag) reach the repo in submission
    // order. The shared [scope] is Dispatchers.IO (multi-threaded): fire-and-
    // forget launches there can grab the repo mutex out of order, letting an
    // older drag frame's offset clobber a newer one. limitedParallelism(1)
    // serializes dispatch; the repo's own debounced file write stays on [scope].
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.Default.limitedParallelism(1)

    // Every edit passes through here, so this is where it can be taken back.
    // Touched only from [writeDispatcher], which is single-threaded, so the
    // deques inside need no locking of their own.
    private val history = EditHistory()

    // What the history holds, mirrored into snapshot state so a toolbar can grey
    // its buttons. The deques themselves stay on the write thread: a Compose read
    // of an ArrayDeque being mutated elsewhere is a race the UI would lose rarely
    // and confusingly.
    private val _canUndo = mutableStateOf(false)
    private val _canRedo = mutableStateOf(false)
    val canUndo: Boolean get() = _canUndo.value
    val canRedo: Boolean get() = _canRedo.value

    /**
     * One edit: what the graph was, the change, and the note that it happened.
     *
     * [key] groups a run of writes into one step a person can take back. Per
     * widget and per axis for the geometry ones, which fire on every frame of a
     * drag; null for a structural change, which is one thing somebody did and
     * never merges with the next.
     */
    private suspend fun edit(
        key: String?,
        validate: Boolean = true,
        transform: (LayoutGraph) -> LayoutGraph,
    ) {
        val before = repo.value()
        repo.update(validate, transform)
        history.record(key, before, repo.value())
        publishHistory()
    }

    private fun publishHistory() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    /** Steps back to the graph before the last edit. Nothing to undo is a no-op. */
    fun undo() {
        scope.launch(writeDispatcher) {
            val previous = history.undo(repo.value()) ?: return@launch
            repo.update { previous }
            publishHistory()
        }
    }

    /** Steps forward again, until the next edit branches away from it. */
    fun redo() {
        scope.launch(writeDispatcher) {
            val next = history.redo(repo.value()) ?: return@launch
            repo.update { next }
            publishHistory()
        }
    }

    // Window-level Ctrl+E increments this tick. The EditorSurfaceHost
    // observes it via snapshotFlow and flips its own edit state. The
    // signal lives on the singleton controller because the keybind is
    // handled at Window scope (focus-independent -- Modifier.onKeyEvent
    // on the host Box only fires when a descendant holds focus, which
    // the side rails steal), while the edit boolean is per-surface-host
    // remember-state. The tick bridges the two. Read-only outward: only
    // requestEditToggle mutates it.
    private val _editToggleSignal = mutableStateOf(0)
    val editToggleSignal: State<Int> = _editToggleSignal

    fun requestEditToggle() {
        _editToggleSignal.value++
    }

    // Whether a host is currently in edit mode. Reported by the host and read by the
    // window's key handler, which is what lets Escape be claimed only while there is
    // an editor to back out of -- every dialog keeps its own Escape the rest of the
    // time. A boolean rather than a count because exactly one host is mounted (see
    // EditorSurfaceHost: the screen crossfade swaps content inside it, not the host).
    private val _editing = mutableStateOf(false)
    val isEditing: Boolean get() = _editing.value

    fun reportEditing(on: Boolean) {
        _editing.value = on
    }

    // Escape (window-level, while editing) bumps this; the host observes it and runs
    // its own staged back-out. Same focus-independent bridge as the edit toggle, and
    // needed for the same reason: a Modifier.onKeyEvent on the host Box fires only
    // while a descendant holds focus, and the rails and the prop panel's fields hold
    // it instead -- so the "Esc -- exit" the editor bar promises did nothing.
    private val _editorEscapeSignal = mutableStateOf(0)
    val editorEscapeSignal: State<Int> = _editorEscapeSignal

    fun requestEditorEscape() {
        _editorEscapeSignal.value++
    }

    // Ctrl+N (window-level) bumps this; ShellRightRegion observes it and flips
    // its own collapsed prop. Same focus-independent bridge as the edit toggle.
    private val _rightRailToggleSignal = mutableStateOf(0)
    val rightRailToggleSignal: State<Int> = _rightRailToggleSignal

    fun requestRightRailToggle() {
        _rightRailToggleSignal.value++
    }

    // `slots` comes from the widget's descriptor and pre-seeds the
    // WidgetInstance.children map with empty SlotContent for every
    // declared slot. Without this, a freshly palette-added container
    // ships with children == emptyMap; LayoutGraph.mutateNested then
    // sees `container.children[slot] == null` and identity-returns
    // when the user tries to drop something INTO the container --
    // the container appears "alive" because the empty placeholder
    // registers bounds, but nothing actually persists. Pre-seeding
    // happens at the editor layer because the LayoutGraph layer
    // intentionally rejects undeclared slots (no auto-create), so
    // typo-protection stays at the model boundary.
    // `placement` seeds an initial position so a palette drop onto a placement
    // slot is born at the drop point (and at a concrete size) rather than
    // flashing at the origin and recomposing. Null for flow slots, which derive
    // the position from the order instead.
    fun addWidget(
        path: SlotPath,
        kind: WidgetKind,
        slots: List<SlotId>,
        index: Int,
        placement: Placement? = null,
        surface: SurfaceSpec? = null,
    ) {
        scope.launch(writeDispatcher) {
            val children = if (slots.isEmpty()) {
                emptyMap()
            } else {
                slots.associateWith { SlotContent() }
            }
            val widget = WidgetInstance(
                kind       = kind,
                instanceId = newInstanceId(),
                children   = children,
                placement  = placement,
                // The widget's own declared plane, so one dropped from the palette
                // looks like the one the bundled layout places. Editable from the
                // moment it lands, because it is written onto the instance rather
                // than consulted behind it.
                surface    = surface,
            )
            edit(key = null) { it.insertWidget(path, widget, index) }
        }
    }

    fun removeWidget(path: SlotPath, instanceId: String) {
        scope.launch(writeDispatcher) {
            edit(key = null) { it.removeWidget(path, instanceId) }
        }
    }

    // Replaces the props of one widget. The editor's prop panel hands
    // over the full JsonObject (default baseline overlaid with the
    // user's edits); an empty object resets the widget to its declared
    // defaults.
    fun updateProps(path: SlotPath, instanceId: String, props: JsonObject) {
        scope.launch(writeDispatcher) {
            // Keyed per widget: a slider in the prop panel emits while it is
            // dragged, and taking that back is one step, not forty.
            edit(key = "props:$instanceId") { it.updateWidgetProps(path, instanceId, props) }
        }
    }

    // The widget's own surface. An all-default one normalizes to null in the
    // transform, so it never bloats the file.
    fun updateSurface(path: SlotPath, instanceId: String, surface: SurfaceSpec?) {
        scope.launch(writeDispatcher) {
            edit(key = "surface:$instanceId") { it.updateWidgetSurface(path, instanceId, surface) }
        }
    }

    fun reorderInSlot(path: SlotPath, fromIndex: Int, toIndex: Int) {
        scope.launch(writeDispatcher) {
            edit(key = null) { it.reorderInSlot(path, fromIndex, toIndex) }
        }
    }

    // Slot mode. A non-null flow derives each child's position from the order;
    // null hands that to the children and seeds one onto any that carries none.
    fun setFlow(path: SlotPath, flow: FlowSpec?) {
        scope.launch(writeDispatcher) { edit(key = null) { it.setFlow(path, flow) } }
    }

    // Placement slot: scale the arrangement to fit (adaptive) or hold it at exact
    // coordinates. Inert on a flow slot, which fills and wraps on its own.
    fun setSlotAdaptive(path: SlotPath, adaptive: Boolean) {
        scope.launch(writeDispatcher) { edit(key = null) { it.setSlotAdaptive(path, adaptive) } }
    }

    // Nudges the line length of a wrapped flow. Reads the current value from the
    // graph INSIDE the serialized update so rapid clicks compose without a
    // lost-update race; the model clamps the result.
    fun nudgeWrap(path: SlotPath, delta: Int) {
        scope.launch(writeDispatcher) {
            edit(key = "wrap:$path") { g ->
                val flow = g.traverse(path)?.flow ?: return@edit g
                g.setFlow(path, flow.copy(wrap = (flow.wrap + delta).coerceIn(0, GRID_MAX)))
            }
        }
    }

    // Nudges the lattice a placement slot measures in. 0 is free placement, so
    // stepping down to it is how a lattice becomes a plain canvas again.
    fun nudgeGrid(path: SlotPath, delta: Int) {
        scope.launch(writeDispatcher) {
            edit(key = "grid:$path") { g ->
                val current = g.traverse(path)?.grid ?: SlotContent().grid
                g.setGrid(path, current + delta)
            }
        }
    }

    // Placement: offset and size in the slot's own unit, plus anchor and paint
    // order. Each composes through the model's updatePlacement, so the five do
    // not clobber one another mid-drag. The geometry ones skip the tree-wide
    // uniqueness sweep: they fire per drag frame and cannot mint an id.
    fun setWidgetOffset(path: SlotPath, instanceId: String, x: Float, y: Float) {
        scope.launch(writeDispatcher) {
            edit("offset:$instanceId", validate = false) { it.setWidgetOffset(path, instanceId, x, y) }
        }
    }

    fun setWidgetSize(path: SlotPath, instanceId: String, width: Float, height: Float) {
        scope.launch(writeDispatcher) {
            edit("size:$instanceId", validate = false) { it.setWidgetSize(path, instanceId, width, height) }
        }
    }

    // Outer spacing around the widget, set from the panel's sliders. Keyed like the
    // other geometry writes so a slider drag coalesces into one history entry.
    fun setWidgetPadding(path: SlotPath, instanceId: String, padding: SurfaceInsets) {
        scope.launch(writeDispatcher) {
            edit("padding:$instanceId", validate = false) { it.setWidgetPadding(path, instanceId, padding) }
        }
    }

    /**
     * Offset and size together, which is what dragging a leading edge changes.
     *
     * One key, so a resize is one step in the history. As two calls it was two
     * keys alternating, and a run whose key changes every frame coalesces into
     * nothing: every frame of the drag would have been its own undo.
     */
    fun setWidgetBounds(path: SlotPath, instanceId: String, x: Float, y: Float, width: Float, height: Float) {
        scope.launch(writeDispatcher) {
            edit("bounds:$instanceId", validate = false) {
                it.setWidgetBounds(path, instanceId, x, y, width, height)
            }
        }
    }

    fun setWidgetZ(path: SlotPath, instanceId: String, z: Int) {
        scope.launch(writeDispatcher) {
            edit("z:$instanceId", validate = false) { it.setWidgetZ(path, instanceId, z) }
        }
    }

    fun setWidgetAnchor(path: SlotPath, instanceId: String, anchor: String) {
        scope.launch(writeDispatcher) { edit(key = null) { it.setWidgetAnchor(path, instanceId, anchor) } }
    }

    // Lattice move and resize: re-anchor a widget to a target cell keeping its
    // span, or grow its span keeping its anchor. Neither moves anybody else. A
    // target that collides snaps to the nearest free cell and a span that would
    // overlap is clamped, because this is a snap grid over free placement and
    // not a packer -- gaps are allowed and stay where the user left them.
    fun moveWidgetInGrid(path: SlotPath, instanceId: String, col: Int, row: Int, columns: Int) {
        scope.launch(writeDispatcher) {
            edit("gridmove:$instanceId", validate = false) { g ->
                val cur = g.traverse(path)?.widgets?.firstOrNull { it.instanceId == instanceId }?.placement
                    ?: Placement()
                g.placeWidgetInGrid(path, instanceId, cur.copy(x = col.toFloat(), y = row.toFloat()), columns)
            }
        }
    }

    fun resizeWidgetInGrid(path: SlotPath, instanceId: String, colSpan: Int, rowSpan: Int, columns: Int) {
        scope.launch(writeDispatcher) {
            // Same reason the four above skip it: this one fires inside a drag loop,
            // and a tree-wide walk per frame is what the flag was added to avoid.
            edit("gridsize:$instanceId", validate = false) {
                it.resizeWidgetInGrid(path, instanceId, colSpan.toFloat(), rowSpan.toFloat(), columns)
            }
        }
    }

    fun moveWidget(from: SlotPath, to: SlotPath, instanceId: String, toIndex: Int) {
        scope.launch(writeDispatcher) {
            edit(key = null) { it.moveWidget(from, to, instanceId, toIndex) }
        }
    }

    // Surface reset = restore from bundled default. Escape hatch for
    // when a non-removable widget ends up out-of-place, or the user
    // wants to undo a chain of edits on one surface without nuking
    // their whole layout.
    //
    // Recorded like any other edit, and that is the point: going back to the
    // default is the move somebody makes when the arrangement has got away from
    // them, and it should not be the one thing they cannot take back.
    fun resetSurface(surface: SurfaceId) {
        scope.launch(writeDispatcher) {
            val before = repo.value()
            repo.resetSurface(surface)
            history.record(key = null, before = before, after = repo.value())
            publishHistory()
        }
    }

    // Full reset to the bundled default across every surface.
    fun resetAll() {
        scope.launch(writeDispatcher) {
            val before = repo.value()
            repo.resetAll()
            history.record(key = null, before = before, after = repo.value())
            publishHistory()
        }
    }

    // UUID minting on palette drop. Matches NotificationCenter.kt's
    // UUID.randomUUID() pattern -- no kotlinx.uuid dep for one call.
    private fun newInstanceId(): String = UUID.randomUUID().toString()
}
