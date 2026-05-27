package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WidgetInstance(
    val kind: WidgetKind,
    @SerialName("instance_id") val instanceId: String,
    val props: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SlotContent(val widgets: List<WidgetInstance> = emptyList())

@Serializable
data class SurfaceLayout(val slots: Map<SlotId, SlotContent> = emptyMap())

@Serializable
data class LayoutGraph(val surfaces: Map<SurfaceId, SurfaceLayout> = emptyMap()) {
    companion object {
        val EMPTY: LayoutGraph = LayoutGraph()
    }
}

// (SurfaceId, SlotId) pair. Editor mutations reference slots by this
// address; passing two value classes around at every call site is
// noisy and prone to argument-swap mistakes.
data class SlotAddress(val surface: SurfaceId, val slot: SlotId)

// Immutable transforms on the graph. Each returns a new LayoutGraph;
// the caller hands the result to LayoutGraphRepository.update. Unknown
// surfaces, slots, or widgets are no-ops -- editor mutations race with
// disk reloads, and a transform on a vanished slot must not crash.
fun LayoutGraph.insertWidget(
    surface: SurfaceId,
    slot: SlotId,
    widget: WidgetInstance,
    index: Int,
): LayoutGraph {
    val layout = surfaces[surface] ?: return this
    val content = layout.slots[slot] ?: return this
    val coerced = index.coerceIn(0, content.widgets.size)
    val updated = content.copy(
        widgets = content.widgets.toMutableList().apply { add(coerced, widget) },
    )
    return withSlot(surface, slot, updated)
}

fun LayoutGraph.removeWidget(
    surface: SurfaceId,
    slot: SlotId,
    instanceId: String,
): LayoutGraph {
    val layout = surfaces[surface] ?: return this
    val content = layout.slots[slot] ?: return this
    if (content.widgets.none { it.instanceId == instanceId }) return this
    return withSlot(
        surface,
        slot,
        content.copy(widgets = content.widgets.filterNot { it.instanceId == instanceId }),
    )
}

fun LayoutGraph.reorderInSlot(
    surface: SurfaceId,
    slot: SlotId,
    fromIndex: Int,
    toIndex: Int,
): LayoutGraph {
    val layout = surfaces[surface] ?: return this
    val content = layout.slots[slot] ?: return this
    if (fromIndex !in content.widgets.indices) return this
    val target = toIndex.coerceIn(0, content.widgets.size - 1)
    if (fromIndex == target) return this
    val reordered = content.widgets.toMutableList().apply {
        add(target, removeAt(fromIndex))
    }
    return withSlot(surface, slot, content.copy(widgets = reordered))
}

fun LayoutGraph.moveWidget(
    from: SlotAddress,
    to: SlotAddress,
    instanceId: String,
    toIndex: Int,
): LayoutGraph {
    val fromContent = surfaces[from.surface]?.slots?.get(from.slot) ?: return this
    val widget = fromContent.widgets.firstOrNull { it.instanceId == instanceId } ?: return this
    if (from == to) {
        val sourceIdx = fromContent.widgets.indexOfFirst { it.instanceId == instanceId }
        return reorderInSlot(from.surface, from.slot, sourceIdx, toIndex)
    }
    if (surfaces[to.surface]?.slots?.get(to.slot) == null) return this
    return this
        .removeWidget(from.surface, from.slot, instanceId)
        .insertWidget(to.surface, to.slot, widget, toIndex)
}

private fun LayoutGraph.withSlot(
    surface: SurfaceId,
    slot: SlotId,
    content: SlotContent,
): LayoutGraph {
    val layout = surfaces[surface] ?: return this
    val newSlots = layout.slots.toMutableMap().apply { put(slot, content) }
    val newSurfaces = surfaces.toMutableMap().apply { put(surface, layout.copy(slots = newSlots)) }
    return copy(surfaces = newSurfaces)
}
