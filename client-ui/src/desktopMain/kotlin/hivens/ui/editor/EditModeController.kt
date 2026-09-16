package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.model.FlowSpec
import hivens.widget.model.GRID_MAX
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
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
import hivens.widget.model.setWidgetAnchor
import hivens.widget.model.setWidgetOffset
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
            repo.update { it.insertWidget(path, widget, index) }
        }
    }

    fun removeWidget(path: SlotPath, instanceId: String) {
        scope.launch(writeDispatcher) {
            repo.update { it.removeWidget(path, instanceId) }
        }
    }

    // Replaces the props of one widget. The editor's prop panel hands
    // over the full JsonObject (default baseline overlaid with the
    // user's edits); an empty object resets the widget to its declared
    // defaults.
    fun updateProps(path: SlotPath, instanceId: String, props: JsonObject) {
        scope.launch(writeDispatcher) {
            repo.update { it.updateWidgetProps(path, instanceId, props) }
        }
    }

    // The widget's own surface. An all-default one normalizes to null in the
    // transform, so it never bloats the file.
    fun updateSurface(path: SlotPath, instanceId: String, surface: SurfaceSpec?) {
        scope.launch(writeDispatcher) {
            repo.update { it.updateWidgetSurface(path, instanceId, surface) }
        }
    }

    fun reorderInSlot(path: SlotPath, fromIndex: Int, toIndex: Int) {
        scope.launch(writeDispatcher) {
            repo.update { it.reorderInSlot(path, fromIndex, toIndex) }
        }
    }

    // Slot mode. A non-null flow derives each child's position from the order;
    // null hands that to the children and seeds one onto any that carries none.
    fun setFlow(path: SlotPath, flow: FlowSpec?) {
        scope.launch(writeDispatcher) { repo.update { it.setFlow(path, flow) } }
    }

    // Nudges the line length of a wrapped flow. Reads the current value from the
    // graph INSIDE the serialized update so rapid clicks compose without a
    // lost-update race; the model clamps the result.
    fun nudgeWrap(path: SlotPath, delta: Int) {
        scope.launch(writeDispatcher) {
            repo.update { g ->
                val flow = g.traverse(path)?.flow ?: return@update g
                g.setFlow(path, flow.copy(wrap = (flow.wrap + delta).coerceIn(0, GRID_MAX)))
            }
        }
    }

    // Nudges the lattice a placement slot measures in. 0 is free placement, so
    // stepping down to it is how a lattice becomes a plain canvas again.
    fun nudgeGrid(path: SlotPath, delta: Int) {
        scope.launch(writeDispatcher) {
            repo.update { g ->
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
        scope.launch(writeDispatcher) { repo.update(validate = false) { it.setWidgetOffset(path, instanceId, x, y) } }
    }

    fun setWidgetSize(path: SlotPath, instanceId: String, width: Float, height: Float) {
        scope.launch(writeDispatcher) { repo.update(validate = false) { it.setWidgetSize(path, instanceId, width, height) } }
    }

    fun setWidgetZ(path: SlotPath, instanceId: String, z: Int) {
        scope.launch(writeDispatcher) { repo.update(validate = false) { it.setWidgetZ(path, instanceId, z) } }
    }

    fun setWidgetAnchor(path: SlotPath, instanceId: String, anchor: String) {
        scope.launch(writeDispatcher) { repo.update { it.setWidgetAnchor(path, instanceId, anchor) } }
    }

    // Lattice move and resize: re-anchor a widget to a target cell keeping its
    // span, or grow its span keeping its anchor. Neither moves anybody else. A
    // target that collides snaps to the nearest free cell and a span that would
    // overlap is clamped, because this is a snap grid over free placement and
    // not a packer -- gaps are allowed and stay where the user left them.
    fun moveWidgetInGrid(path: SlotPath, instanceId: String, col: Int, row: Int, columns: Int) {
        scope.launch(writeDispatcher) {
            repo.update(validate = false) { g ->
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
            repo.update(validate = false) { it.resizeWidgetInGrid(path, instanceId, colSpan.toFloat(), rowSpan.toFloat(), columns) }
        }
    }

    fun moveWidget(from: SlotPath, to: SlotPath, instanceId: String, toIndex: Int) {
        scope.launch(writeDispatcher) {
            repo.update { it.moveWidget(from, to, instanceId, toIndex) }
        }
    }

    // Surface reset = restore from bundled default. Escape hatch for
    // when a non-removable widget ends up out-of-place, or the user
    // wants to undo a chain of edits on one surface without nuking
    // their whole layout.
    fun resetSurface(surface: SurfaceId) {
        scope.launch(writeDispatcher) {
            repo.resetSurface(surface)
        }
    }

    // Full reset to the bundled default across every surface.
    fun resetAll() {
        scope.launch(writeDispatcher) { repo.resetAll() }
    }

    // UUID minting on palette drop. Matches NotificationCenter.kt's
    // UUID.randomUUID() pattern -- no kotlinx.uuid dep for one call.
    private fun newInstanceId(): String = UUID.randomUUID().toString()
}
