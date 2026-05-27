package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse

// Renders every widget at the addressed slot. Two entry forms:
//
//   * Top-level: SlotRenderer(surface, slot) -- used by surface
//     composables (NewHomeScreen, LibraryScreen, AppLayout rails, ...).
//     Initialises LocalSlotPath at the surface root.
//
//   * Nested: SlotRenderer(parent, slot) -- used by container widgets
//     inside their @Composable body. Extends LocalSlotPath with the
//     container's instanceId so the editor's drop-target registry
//     distinguishes "slot 'body' on container X" from "slot 'body' on
//     container Y".
//
// Each widget renders through LocalWidgetDecorator. Default decorator
// is identity -- zero cost when no editor is mounted. Unknown widget
// kinds (layout file references a kind the registry no longer ships)
// render nothing; the slot stays valid and the diagnostic surfaces in
// the editor's --audit-widgets dev tool.
@Composable
fun SlotRenderer(surface: SurfaceId, slot: SlotId) {
    val path = SlotPath(surface, slot)
    CompositionLocalProvider(LocalSlotPath provides path) {
        RenderSlotContent(path)
    }
}

@Composable
fun SlotRenderer(parent: WidgetInstance, slot: SlotId) {
    val parentPath = LocalSlotPath.current
    val childPath = parentPath.child(parent.instanceId, slot)
    CompositionLocalProvider(LocalSlotPath provides childPath) {
        RenderSlotContent(childPath)
    }
}

@Composable
private fun RenderSlotContent(path: SlotPath) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val emptyDecorator = LocalEmptySlotDecorator.current

    val content: SlotContent = graph.traverse(path) ?: SlotContent()
    val address = path.leafAddress

    if (content.widgets.isEmpty()) {
        emptyDecorator(address)
        return
    }
    content.widgets.forEachIndexed { index, instance ->
        val descriptor = registry[instance.kind] ?: return@forEachIndexed
        decorator(address, index, descriptor, instance) {
            descriptor.Render(instance)
        }
    }
}
