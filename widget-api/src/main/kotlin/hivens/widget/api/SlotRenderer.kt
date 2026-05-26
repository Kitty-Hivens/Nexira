package hivens.widget.api

import androidx.compose.runtime.Composable

// Looks up the slot in the active LayoutGraph and emits its widgets in
// order. The active graph is provided via LocalLayoutGraph from the
// hosting application; the registry is provided via LocalWidgetRegistry.
// Both locals are wired in the launcher's Koin bootstrap.
//
// An unknown WidgetKind (e.g. layout file refers to a plugin widget the
// registry no longer ships) renders nothing -- the slot stays valid;
// the diagnostic shows up in the --audit-widgets dev tool (kernel-4).
@Composable
fun SlotRenderer(surface: SurfaceId, slot: SlotId) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current
    val widgets = graph.surfaces[surface]?.slots?.get(slot)?.widgets.orEmpty()
    widgets.forEach { instance ->
        val descriptor = registry[instance.kind] ?: return@forEach
        descriptor.Render(instance)
    }
}
