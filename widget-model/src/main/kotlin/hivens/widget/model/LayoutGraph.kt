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
    // Phase G: relative size along the slot's main axis when the slot is
    // Row/Column. 0 = natural/wrap size; > 0 = a weighted share. Set via
    // the edit-mode drag-dividers.
    val weight: Float = 0f,
)

// Phase G: how a slot arranges its widgets. Column (default) reproduces
// the pre-Phase-G vertical stack; Row lays them horizontally; Grid flows
// them into `gridColumns` uniform cells.
@Serializable
enum class SlotOrientation { Column, Row, Grid }

@Serializable
data class SlotContent(
    val widgets: List<WidgetInstance> = emptyList(),
    val orientation: SlotOrientation = SlotOrientation.Column,
    // Column count when orientation == Grid; ignored otherwise.
    val gridColumns: Int = 2,
)

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

// Replaces the props JsonObject on a single widget addressed by
// (path, instanceId). No-op if the slot or the instance is gone --
// editor prop edits race with disk reloads, same contract as the other
// transforms.
fun LayoutGraph.updateWidgetProps(
    path: SlotPath,
    instanceId: String,
    props: JsonObject,
): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else content.copy(
            widgets = content.widgets.map {
                if (it.instanceId == instanceId) it.copy(props = props) else it
            },
        )
    }

// Phase G layout transforms. Slot orientation + grid column count are
// slot-level; widget weight is per-instance. Each is a no-op (identity
// return) when the value is unchanged or the slot/instance is missing --
// same contract as the transforms above.
fun LayoutGraph.setSlotOrientation(path: SlotPath, orientation: SlotOrientation): LayoutGraph =
    mutate(path) { content ->
        if (content.orientation == orientation) content
        else content.copy(orientation = orientation)
    }

fun LayoutGraph.setGridColumns(path: SlotPath, columns: Int): LayoutGraph =
    mutate(path) { content ->
        val coerced = columns.coerceAtLeast(1)
        if (content.gridColumns == coerced) content
        else content.copy(gridColumns = coerced)
    }

fun LayoutGraph.setWidgetWeight(path: SlotPath, instanceId: String, weight: Float): LayoutGraph =
    mutate(path) { content ->
        val w = weight.coerceAtLeast(0f)
        val target = content.widgets.firstOrNull { it.instanceId == instanceId } ?: return@mutate content
        if (target.weight == w) return@mutate content
        content.copy(
            widgets = content.widgets.map {
                if (it.instanceId == instanceId) it.copy(weight = w) else it
            },
        )
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

// All instanceIds under one surface, tree-wide (including nested children).
fun SurfaceLayout.instanceIds(): Set<String> =
    slots.values.flatMap { content -> content.walkInstances().map { it.instanceId } }.toSet()

// Removes every widget whose instanceId is in `ids`, tree-wide. resetSurface
// uses this to clear ids that leaked onto OTHER surfaces (via a cross-surface
// move) before restoring a default surface -- otherwise the restored default
// ids collide with the leaked copies and the tree-wide uniqueness check
// rejects the whole reset, trapping the user.
fun SurfaceLayout.removeInstanceIds(ids: Set<String>): SurfaceLayout =
    copy(slots = slots.mapValues { (_, content) -> content.removeInstanceIds(ids) })

private fun SlotContent.removeInstanceIds(ids: Set<String>): SlotContent =
    copy(
        widgets = widgets
            .filter { it.instanceId !in ids }
            .map { w -> w.copy(children = w.children.mapValues { (_, c) -> c.removeInstanceIds(ids) }) },
    )

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
// Transitional flat-coords forms. Deprecated to nudge callers onto the
// SlotPath canonical form -- nested containers in Phase A.3 onward
// cannot be expressed as (SurfaceId, SlotId) alone. Compat lives until
// every in-tree caller migrates (tracked in the LayoutGraphMutations
// test suite, which exercises the SlotPath path directly).

@Deprecated(
    message     = "Use SlotPath -- (SurfaceId, SlotId) cannot address nested container slots.",
    replaceWith = ReplaceWith(
        "insertWidget(SlotPath(surface, slot), widget, index)",
        "hivens.widget.model.SlotPath",
    ),
)
fun LayoutGraph.insertWidget(
    surface: SurfaceId,
    slot: SlotId,
    widget: WidgetInstance,
    index: Int,
): LayoutGraph = insertWidget(SlotPath(surface, slot), widget, index)

@Deprecated(
    message     = "Use SlotPath -- (SurfaceId, SlotId) cannot address nested container slots.",
    replaceWith = ReplaceWith(
        "removeWidget(SlotPath(surface, slot), instanceId)",
        "hivens.widget.model.SlotPath",
    ),
)
fun LayoutGraph.removeWidget(
    surface: SurfaceId,
    slot: SlotId,
    instanceId: String,
): LayoutGraph = removeWidget(SlotPath(surface, slot), instanceId)

@Deprecated(
    message     = "Use SlotPath -- (SurfaceId, SlotId) cannot address nested container slots.",
    replaceWith = ReplaceWith(
        "reorderInSlot(SlotPath(surface, slot), fromIndex, toIndex)",
        "hivens.widget.model.SlotPath",
    ),
)
fun LayoutGraph.reorderInSlot(
    surface: SurfaceId,
    slot: SlotId,
    fromIndex: Int,
    toIndex: Int,
): LayoutGraph = reorderInSlot(SlotPath(surface, slot), fromIndex, toIndex)

@Deprecated(
    message     = "Use SlotPath -- SlotAddress cannot address nested container slots.",
    replaceWith = ReplaceWith(
        "moveWidget(from.toPath(), to.toPath(), instanceId, toIndex)",
        "hivens.widget.model.toPath",
    ),
)
fun LayoutGraph.moveWidget(
    from: SlotAddress,
    to: SlotAddress,
    instanceId: String,
    toIndex: Int,
): LayoutGraph = moveWidget(from.toPath(), to.toPath(), instanceId, toIndex)
