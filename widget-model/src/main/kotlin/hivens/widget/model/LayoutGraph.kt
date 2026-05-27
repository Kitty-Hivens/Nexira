package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Compose stability of this type is intentionally NOT marked here --
// @Immutable lives in androidx.compose.runtime and widget-model must
// stay Compose-free (CLI / TUI / launcher consumers don't carry the
// Compose runtime). Stability is addressed when Phase B.5 introduces
// typed-props, replacing JsonObject with serializer-registry-backed
// instances that satisfy Compose's auto-stability heuristics.
@Serializable
data class WidgetInstance(
    val kind: WidgetKind,
    @SerialName("instance_id") val instanceId: String,
    val props: JsonObject = JsonObject(emptyMap()),
    // Sub-widgets for container kinds. Keyed by SlotId of a slot the
    // descriptor declared via @Widget(slots = ...). Empty for leaves.
    // Held as a typed field rather than smuggled inside `props` because
    // future mixin hooks (transformProps in Phase C) must not be able
    // to corrupt the layout tree.
    val children: Map<SlotId, SlotContent> = emptyMap(),
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

// (SurfaceId, SlotId) pair. Retained for chrome-level callers that
// only care about the leaf coordinates and do not navigate the path.
// Internal transforms operate on SlotPath; SlotAddress.toPath() bridges
// when needed.
data class SlotAddress(val surface: SurfaceId, val slot: SlotId)

// Immutable transforms. Each returns a new LayoutGraph; the caller
// hands the result to LayoutGraphRepository.update. Unknown surfaces,
// slots, or widget instances are no-ops -- editor mutations race with
// disk reloads, and a transform on a vanished slot must not crash.

fun LayoutGraph.insertWidget(path: SlotPath, widget: WidgetInstance, index: Int): LayoutGraph =
    mutate(path) { content ->
        val coerced = index.coerceIn(0, content.widgets.size)
        content.copy(widgets = content.widgets.toMutableList().apply { add(coerced, widget) })
    }

fun LayoutGraph.removeWidget(path: SlotPath, instanceId: String): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else content.copy(widgets = content.widgets.filterNot { it.instanceId == instanceId })
    }

fun LayoutGraph.reorderInSlot(path: SlotPath, fromIndex: Int, toIndex: Int): LayoutGraph =
    mutate(path) { content ->
        if (fromIndex !in content.widgets.indices) return@mutate content
        val target = toIndex.coerceIn(0, content.widgets.size - 1)
        if (fromIndex == target) return@mutate content
        content.copy(
            widgets = content.widgets.toMutableList().apply { add(target, removeAt(fromIndex)) },
        )
    }

fun LayoutGraph.moveWidget(
    from: SlotPath,
    to: SlotPath,
    instanceId: String,
    toIndex: Int,
): LayoutGraph {
    // Cycle guard: a container cannot be dropped inside its own subtree.
    if (to.nested.any { it.parentInstanceId == instanceId }) return this

    val fromContent = traverse(from) ?: return this
    val widget = fromContent.widgets.firstOrNull { it.instanceId == instanceId } ?: return this

    if (from == to) {
        val sourceIdx = fromContent.widgets.indexOfFirst { it.instanceId == instanceId }
        return reorderInSlot(from, sourceIdx, toIndex)
    }

    // Destination must exist (top-level slot or nested container slot).
    if (traverse(to) == null) return this

    return removeWidget(from, instanceId).insertWidget(to, widget, toIndex)
}

// Walks the path and returns the SlotContent at the leaf, or null if
// any intermediate surface / slot / parent widget is missing.
fun LayoutGraph.traverse(path: SlotPath): SlotContent? {
    var content = surfaces[path.surface]?.slots?.get(path.rootSlot) ?: return null
    for (segment in path.nested) {
        val container = content.widgets.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return null
        content = container.children[segment.slot] ?: return null
    }
    return content
}

// Walks every WidgetInstance in the graph (including nested children)
// in pre-order. Used by the launcher's tree-wide instanceId uniqueness
// check.
fun LayoutGraph.walkInstances(): Sequence<WidgetInstance> = sequence {
    for ((_, layout) in surfaces) {
        for ((_, content) in layout.slots) {
            yieldAll(content.walkInstances())
        }
    }
}

private fun SlotContent.walkInstances(): Sequence<WidgetInstance> = sequence {
    for (widget in widgets) {
        yield(widget)
        for ((_, child) in widget.children) {
            yieldAll(child.walkInstances())
        }
    }
}

// ── Internal traversal + rebuild ──────────────────────────────────────

// Applies `mutator` to the SlotContent at `path`. If the mutator
// returns the same content reference, the graph is returned unchanged
// (`===` identity preserved by callers so no-op transforms allocate
// nothing). Otherwise rebuilds the chain back up to the surface.
private fun LayoutGraph.mutate(
    path: SlotPath,
    mutator: (SlotContent) -> SlotContent,
): LayoutGraph {
    val rootLayout = surfaces[path.surface] ?: return this
    val rootContent = rootLayout.slots[path.rootSlot] ?: return this

    val newRootContent: SlotContent = if (path.nested.isEmpty()) {
        mutator(rootContent)
    } else {
        val descendantId = path.nested.first().parentInstanceId
        val container = rootContent.widgets.firstOrNull { it.instanceId == descendantId } ?: return this
        val updated = mutateNested(container, path.nested, mutator)
        if (updated === container) return this
        rootContent.copy(widgets = rootContent.widgets.map {
            if (it.instanceId == descendantId) updated else it
        })
    }

    if (newRootContent === rootContent) return this
    val newSlots = rootLayout.slots.toMutableMap().apply { put(path.rootSlot, newRootContent) }
    val newSurfaces = surfaces.toMutableMap().apply { put(path.surface, rootLayout.copy(slots = newSlots)) }
    return copy(surfaces = newSurfaces)
}

private fun mutateNested(
    container: WidgetInstance,
    pathFromContainer: List<NestedSegment>,
    mutator: (SlotContent) -> SlotContent,
): WidgetInstance {
    val segment = pathFromContainer.first()
    val rest = pathFromContainer.drop(1)
    val childContent = container.children[segment.slot] ?: return container

    val newChildContent: SlotContent = if (rest.isEmpty()) {
        mutator(childContent)
    } else {
        val deeperId = rest.first().parentInstanceId
        val deeperContainer = childContent.widgets.firstOrNull { it.instanceId == deeperId } ?: return container
        val updated = mutateNested(deeperContainer, rest, mutator)
        if (updated === deeperContainer) return container
        childContent.copy(widgets = childContent.widgets.map {
            if (it.instanceId == deeperId) updated else it
        })
    }

    if (newChildContent === childContent) return container
    val newChildren = container.children.toMutableMap().apply { put(segment.slot, newChildContent) }
    return container.copy(children = newChildren)
}

// ── Compat overloads ──────────────────────────────────────────────────
// These delegate to the SlotPath form; callers that still hand flat
// (surface, slot) pairs keep working. New code should construct
// SlotPath directly.

fun LayoutGraph.insertWidget(
    surface: SurfaceId,
    slot: SlotId,
    widget: WidgetInstance,
    index: Int,
): LayoutGraph = insertWidget(SlotPath(surface, slot), widget, index)

fun LayoutGraph.removeWidget(
    surface: SurfaceId,
    slot: SlotId,
    instanceId: String,
): LayoutGraph = removeWidget(SlotPath(surface, slot), instanceId)

fun LayoutGraph.reorderInSlot(
    surface: SurfaceId,
    slot: SlotId,
    fromIndex: Int,
    toIndex: Int,
): LayoutGraph = reorderInSlot(SlotPath(surface, slot), fromIndex, toIndex)

fun LayoutGraph.moveWidget(
    from: SlotAddress,
    to: SlotAddress,
    instanceId: String,
    toIndex: Int,
): LayoutGraph = moveWidget(from.toPath(), to.toPath(), instanceId, toIndex)
