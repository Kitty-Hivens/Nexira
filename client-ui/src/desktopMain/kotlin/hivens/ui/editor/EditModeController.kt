package hivens.ui.editor

import hivens.launcher.LayoutGraphRepository
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.insertWidget
import hivens.widget.model.moveWidget
import hivens.widget.model.removeWidget
import hivens.widget.model.reorderInSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

// Single mutation entry point for the editor. Every operation resolves
// to one LayoutGraphRepository.update { transform } call -- the repo
// already handles atomic write + StateFlow re-emission, so the
// controller is just an ergonomic facade.
//
// Methods fire-and-forget on the supplied scope. The repo's StateFlow
// drives recomposition; the caller never awaits the write.
class EditModeController(
    private val repo: LayoutGraphRepository,
    private val scope: CoroutineScope,
) {
    fun addWidget(surface: SurfaceId, slot: SlotId, kind: WidgetKind, index: Int) {
        scope.launch {
            val widget = WidgetInstance(kind = kind, instanceId = newInstanceId())
            repo.update { it.insertWidget(surface, slot, widget, index) }
        }
    }

    fun removeWidget(surface: SurfaceId, slot: SlotId, instanceId: String) {
        scope.launch {
            repo.update { it.removeWidget(surface, slot, instanceId) }
        }
    }

    fun reorderInSlot(surface: SurfaceId, slot: SlotId, fromIndex: Int, toIndex: Int) {
        scope.launch {
            repo.update { it.reorderInSlot(surface, slot, fromIndex, toIndex) }
        }
    }

    fun moveWidget(from: SlotAddress, to: SlotAddress, instanceId: String, toIndex: Int) {
        scope.launch {
            repo.update { it.moveWidget(from, to, instanceId, toIndex) }
        }
    }

    // UUID minting on palette drop. Matches NotificationCenter.kt's
    // UUID.randomUUID() pattern -- no kotlinx.uuid dep for one call.
    private fun newInstanceId(): String = UUID.randomUUID().toString()
}
