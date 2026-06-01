package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import hivens.launcher.LayoutGraphRepository
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetChrome
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.insertWidget
import hivens.widget.model.moveWidget
import hivens.widget.model.removeWidget
import hivens.widget.model.reorderInSlot
import hivens.widget.model.setGridColumns
import hivens.widget.model.setSlotOrientation
import hivens.widget.model.setWidgetWeight
import hivens.widget.model.updateWidgetChrome
import hivens.widget.model.updateWidgetProps
import kotlinx.coroutines.CoroutineScope
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
    fun addWidget(path: SlotPath, kind: WidgetKind, slots: List<SlotId>, index: Int) {
        scope.launch {
            val children = if (slots.isEmpty()) {
                emptyMap()
            } else {
                slots.associateWith { SlotContent() }
            }
            val widget = WidgetInstance(
                kind       = kind,
                instanceId = newInstanceId(),
                children   = children,
            )
            repo.update { it.insertWidget(path, widget, index) }
        }
    }

    fun removeWidget(path: SlotPath, instanceId: String) {
        scope.launch {
            repo.update { it.removeWidget(path, instanceId) }
        }
    }

    // Replaces the props of one widget. The editor's prop panel hands
    // over the full JsonObject (default baseline overlaid with the
    // user's edits); an empty object resets the widget to its declared
    // defaults.
    fun updateProps(path: SlotPath, instanceId: String, props: JsonObject) {
        scope.launch {
            repo.update { it.updateWidgetProps(path, instanceId, props) }
        }
    }

    // Per-instance backing chrome (glass / corner / padding). A no-backing
    // chrome normalizes to null in the transform, so it never bloats the file.
    fun updateChrome(path: SlotPath, instanceId: String, chrome: WidgetChrome?) {
        scope.launch {
            repo.update { it.updateWidgetChrome(path, instanceId, chrome) }
        }
    }

    fun reorderInSlot(path: SlotPath, fromIndex: Int, toIndex: Int) {
        scope.launch {
            repo.update { it.reorderInSlot(path, fromIndex, toIndex) }
        }
    }

    // Phase G slot layout. Orientation + grid columns are slot-level;
    // widget weight is per-instance (set by the drag-dividers in G4).
    fun setSlotOrientation(path: SlotPath, orientation: SlotOrientation) {
        scope.launch { repo.update { it.setSlotOrientation(path, orientation) } }
    }

    fun setGridColumns(path: SlotPath, columns: Int) {
        scope.launch { repo.update { it.setGridColumns(path, columns) } }
    }

    fun setWidgetWeight(path: SlotPath, instanceId: String, weight: Float) {
        scope.launch { repo.update { it.setWidgetWeight(path, instanceId, weight) } }
    }

    fun moveWidget(from: SlotPath, to: SlotPath, instanceId: String, toIndex: Int) {
        scope.launch {
            repo.update { it.moveWidget(from, to, instanceId, toIndex) }
        }
    }

    // Surface reset = restore from bundled default. Escape hatch for
    // when a non-removable widget ends up out-of-place, or the user
    // wants to undo a chain of edits on one surface without nuking
    // their whole layout.
    fun resetSurface(surface: SurfaceId) {
        scope.launch {
            repo.resetSurface(surface)
        }
    }

    // UUID minting on palette drop. Matches NotificationCenter.kt's
    // UUID.randomUUID() pattern -- no kotlinx.uuid dep for one call.
    private fun newInstanceId(): String = UUID.randomUUID().toString()
}
