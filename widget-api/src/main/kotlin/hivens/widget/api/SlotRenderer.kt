package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

// Looks up the slot in the active LayoutGraph and emits its widgets in
// order. The active graph is provided via LocalLayoutGraph from the
// hosting application; the registry is provided via LocalWidgetRegistry.
// Both locals are wired in the launcher's Koin bootstrap.
//
// Each widget renders through LocalWidgetDecorator. Default decorator
// is identity -- zero decoration cost. The editor in :client-ui swaps
// in a chrome wrapper that adds drag handles, remove buttons, and
// pointer listeners. SlotRenderer itself stays editor-agnostic.
//
// An unknown WidgetKind (e.g. layout file refers to a plugin widget the
// registry no longer ships) renders nothing -- the slot stays valid;
// the diagnostic shows up in the --audit-widgets dev tool (kernel-4).
@Composable
fun SlotRenderer(surface: SurfaceId, slot: SlotId) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val widgets = graph.surfaces[surface]?.slots?.get(slot)?.widgets.orEmpty()
    val address = SlotAddress(surface, slot)
    widgets.forEachIndexed { index, instance ->
        val descriptor = registry[instance.kind] ?: return@forEachIndexed
        decorator(address, index, descriptor, instance) {
            descriptor.Render(instance)
        }
    }
}
