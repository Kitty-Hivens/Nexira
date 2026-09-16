package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Compose stability of this type is intentionally NOT marked here --
// @Immutable lives in androidx.compose.runtime and widget-model must
// stay Compose-free (CLI / TUI / launcher consumers don't carry the
// Compose runtime).
@Serializable
data class WidgetInstance(
    val kind: WidgetKind,
    @SerialName("instance_id") val instanceId: String,
    val props: JsonObject = JsonObject(emptyMap()),
    // Sub-widgets for container kinds. Keyed by SlotId of a slot the
    // descriptor declared via @Widget(slots = ...). Empty for leaves.
    // Held as a typed field rather than smuggled inside `props` because
    // future mixin hooks must not be able to corrupt the layout tree.
    val children: Map<SlotId, SlotContent> = emptyMap(),
    /**
     * Where this widget sits and how big it is. Null means nothing has said,
     * which is what lets a slot flipping into placement mode tell the widgets
     * it must seed from the ones already arranged. A flow slot reads the weight
     * and the two sizes out of it and ignores the rest.
     */
    val placement: Placement? = null,
    // Per-instance surface the kernel paints around the widget. Null = none --
    // back-compat default for a widget that wants no plane of its own. The
    // editor's "Surface" section sets it on ANY widget, propless included.
    // Rendered in production via LocalWidgetSurfaceRenderer.
    val surface: SurfaceSpec? = null,
)

/**
 * One slot's children, and how they are arranged.
 *
 * [flow] non-null derives each child's position from the sequence. Null hands
 * that to each child's own [WidgetInstance.placement]. [grid] is the unit that
 * placement is measured in: 0 is one dp, N is one cell of an N-column lattice.
 *
 * A slot in flow mode ignores [grid], and a slot in placement mode ignores
 * [flow] by being null. Neither is cleared when the mode changes, so flipping a
 * slot and flipping it back costs nothing.
 */
@Serializable
data class SlotContent(
    val widgets: List<WidgetInstance> = emptyList(),
    val flow: FlowSpec? = FlowSpec.Column,
    val grid: Int = 0,
)

/** Upper bound for [SlotContent.grid] and for [FlowSpec.wrap]; the steppers clamp to it. */
const val GRID_MAX = 48

@Serializable
data class SurfaceLayout(val slots: Map<SlotId, SlotContent> = emptyMap())

@Serializable
data class LayoutGraph(val surfaces: Map<SurfaceId, SurfaceLayout> = emptyMap()) {
    companion object {
        val EMPTY: LayoutGraph = LayoutGraph()
    }
}

// (SurfaceId, SlotId) pair. Retained for surface-level callers that
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
    val target = traverse(to) ?: return this

    // A widget arriving in a placement slot needs somewhere to be. It used to
    // arrive with whatever it carried from a flow slot, which is nothing, and
    // then drew at the slot's origin on top of whatever was already there. The
    // seed is the same one a slot flipping into placement mode hands out, so a
    // widget that walks in and a widget that was already there are placed by
    // one rule.
    val seeded = if (target.flow == null && widget.placement == null) {
        widget.copy(placement = seedPlacement(target.widgets.size, target.grid, target.widgets))
    } else {
        widget
    }

    return removeWidget(from, instanceId).insertWidget(to, seeded, toIndex)
}

// Replaces the props JsonObject on a single widget addressed by
// (path, instanceId). No-op if the slot or the instance is gone --
// editor prop edits race with disk reloads, same contract as the other
// transforms.
fun LayoutGraph.updateWidgetProps(
    path: SlotPath,
    instanceId: String,
    props: JsonObject,
): LayoutGraph = updateInstance(path, instanceId) { it.copy(props = props) }

// Sets (or clears, with null) the per-instance surface. Same no-op /
// missing-instance contract as updateWidgetProps. An all-default surface
// normalizes to null so the field stays absent for default-styled widgets.
fun LayoutGraph.updateWidgetSurface(
    path: SlotPath,
    instanceId: String,
    surface: SurfaceSpec?,
): LayoutGraph {
    val normalized = surface?.takeUnless { it == SurfaceSpec() }
    return updateInstance(path, instanceId) { it.copy(surface = normalized) }
}

// Every per-instance transform is the same three steps: find the instance in the
// slot, leave the graph alone when [edit] returns what was already there, and
// otherwise rebuild the list with that one widget replaced.
//
// Returning the same SlotContent is what makes a no-op a real one: [mutate]
// decides by reference identity, so an edit that changes nothing has to hand the
// same object back rather than an equal copy.
private fun LayoutGraph.updateInstance(
    path: SlotPath,
    instanceId: String,
    edit: (WidgetInstance) -> WidgetInstance,
): LayoutGraph = mutate(path) { content ->
    val target = content.widgets.firstOrNull { it.instanceId == instanceId } ?: return@mutate content
    val next = edit(target)
    if (next == target) return@mutate content
    content.copy(widgets = content.widgets.map { if (it.instanceId == instanceId) next else it })
}

// ── Slot mode ────────────────────────────────────────────────────────

/**
 * Sets the slot's arrangement. Null puts it in placement mode and seeds a
 * position onto every widget that does not carry one, so nothing piles at the
 * origin. Widgets already placed keep what they had, which makes the flip
 * idempotent.
 */
fun LayoutGraph.setFlow(path: SlotPath, flow: FlowSpec?): LayoutGraph =
    mutate(path) { content ->
        if (content.flow == flow) return@mutate content
        if (flow != null) return@mutate content.copy(flow = flow)
        content.copy(flow = null, widgets = seedPlacements(content.widgets, content.grid))
    }

/**
 * Sets the unit a placement slot measures in: 0 for dp, N for an N-column
 * lattice. Clamped to [GRID_MAX]. Changing it does not rewrite the positions
 * already stored, because a number that means cells and a number that means dp
 * are the user's to reinterpret, and silently rescaling would move everything
 * under them.
 */
fun LayoutGraph.setGrid(path: SlotPath, grid: Int): LayoutGraph =
    mutate(path) { content ->
        val coerced = grid.coerceIn(0, GRID_MAX)
        if (content.grid == coerced) content else content.copy(grid = coerced)
    }

// ── Placement ────────────────────────────────────────────────────────

fun LayoutGraph.setWidgetOffset(path: SlotPath, instanceId: String, x: Float, y: Float): LayoutGraph =
    updatePlacement(path, instanceId) { it.copy(x = x, y = y) }

fun LayoutGraph.setWidgetSize(path: SlotPath, instanceId: String, width: Float, height: Float): LayoutGraph =
    updatePlacement(path, instanceId) {
        it.copy(width = width.coerceAtLeast(0f), height = height.coerceAtLeast(0f))
    }

fun LayoutGraph.setWidgetZ(path: SlotPath, instanceId: String, z: Int): LayoutGraph =
    updatePlacement(path, instanceId) { it.copy(z = z) }

fun LayoutGraph.setWidgetAnchor(path: SlotPath, instanceId: String, anchor: String): LayoutGraph =
    updatePlacement(path, instanceId) { it.copy(anchor = parseAnchor(anchor)) }

fun LayoutGraph.setWidgetWeight(path: SlotPath, instanceId: String, weight: Float): LayoutGraph =
    updatePlacement(path, instanceId) { it.copy(weight = weight.coerceAtLeast(0f)) }

/**
 * Reads the widget's current placement (or the default when it carries none),
 * applies [edit], and writes it back, so offset, size, z, anchor and weight
 * edits compose without clobbering one another mid-drag.
 */
private fun LayoutGraph.updatePlacement(
    path: SlotPath,
    instanceId: String,
    edit: (Placement) -> Placement,
): LayoutGraph = updateInstance(path, instanceId) { widget ->
    val had = widget.placement
    val next = edit(had ?: Placement())
    // Nothing becomes nothing, but something never becomes nothing. Writing a
    // field its own value on an unplaced widget has to stay a no-op, or the
    // identity contract every transform rests on breaks. Going the other way and
    // normalising a placed widget back to null would erase the difference between
    // "at the origin" and "nowhere": a widget dragged to (0, 0) would read as
    // unseeded, and the next flip, move or neighbour's drag would pick it up and
    // put it somewhere else.
    widget.copy(placement = if (had == null && next == Placement()) null else next)
}

// ── Seeding ──────────────────────────────────────────────────────────

/**
 * A position for the widget at [index] in a slot that measures in [grid].
 *
 * Free placement staggers into rows of three so a fresh set does not pile at the
 * origin. A lattice takes the first free cell in reading order, so widgets land
 * the way they read. Pure and deterministic, so the cascade is unit-testable.
 */
fun seedPlacement(index: Int, grid: Int, existing: List<WidgetInstance> = emptyList()): Placement {
    if (grid <= 0) {
        val columns = 3
        return Placement(
            x = FREE_MARGIN + (index % columns) * FREE_CELL_W,
            y = FREE_MARGIN + (index / columns) * FREE_CELL_H,
            z = index,
        )
    }
    val taken = existing.mapNotNull { it.placement }
    var scan = 0
    while (occupiesCell(taken, scan % grid, scan / grid)) scan++
    return Placement(x = (scan % grid).toFloat(), y = (scan / grid).toFloat(), width = 1f, height = 1f)
}

/** Fills in a position for every widget that carries none, leaving the rest alone. */
fun seedPlacements(widgets: List<WidgetInstance>, grid: Int): List<WidgetInstance> {
    if (widgets.none { it.placement == null }) return widgets
    val settled = widgets.filter { it.placement != null }.toMutableList()
    return widgets.mapIndexed { index, w ->
        if (w.placement != null) return@mapIndexed w
        val seeded = w.copy(placement = seedPlacement(index, grid, settled))
        settled.add(seeded)
        seeded
    }
}

private const val FREE_MARGIN = 16f
private const val FREE_CELL_W = 220f
private const val FREE_CELL_H = 160f

// ── Lattice collision ────────────────────────────────────────────────

private fun cellsOverlap(x: Float, y: Float, w: Float, h: Float, other: Placement): Boolean =
    x < other.x + other.spanW() && other.x < x + w &&
        y < other.y + other.spanH() && other.y < y + h

private fun Placement.spanW(): Float = if (width <= 0f) 1f else width
private fun Placement.spanH(): Float = if (height <= 0f) 1f else height

private fun occupiesCell(taken: List<Placement>, col: Int, row: Int): Boolean =
    taken.any { cellsOverlap(col.toFloat(), row.toFloat(), 1f, 1f, it) }

/**
 * Moves [instanceId] to [target] inside a lattice slot, keeping its span.
 *
 * No overlap and no compaction: a target that collides snaps to the nearest free
 * anchor instead, and every other widget stays exactly where it is. Gaps are
 * allowed, because the model is a snap grid laid over free placement and not a
 * packer. Identity when nothing moves.
 */
fun placeInGrid(content: SlotContent, movedId: String, target: Placement, columns: Int): SlotContent {
    val cols = columns.coerceIn(1, GRID_MAX)
    val seeded = seedPlacements(content.widgets, cols)
    val w = target.spanW().coerceIn(1f, cols.toFloat())
    val h = target.spanH().coerceAtLeast(1f)
    val others = seeded.filter { it.instanceId != movedId }.mapNotNull { it.placement }
    val (col, row) = nearestFreeAnchor(
        target.x.coerceIn(0f, (cols - w).coerceAtLeast(0f)),
        target.y.coerceAtLeast(0f),
        w, h, cols, others,
    )
    return applyPlacement(content, seeded, movedId) {
        it.copy(x = col, y = row, width = w, height = h)
    }
}

/**
 * Grows [instanceId] toward [width] by [height] cells from its own anchor,
 * clamped to the largest span that stays free. Other widgets are fixed, so a
 * resize never evicts a neighbour.
 */
fun resizeInGrid(content: SlotContent, movedId: String, width: Float, height: Float, columns: Int): SlotContent {
    val cols = columns.coerceIn(1, GRID_MAX)
    val seeded = seedPlacements(content.widgets, cols)
    val cur = seeded.firstOrNull { it.instanceId == movedId }?.placement ?: Placement()
    val others = seeded.filter { it.instanceId != movedId }.mapNotNull { it.placement }
    val maxW = (cols - cur.x).coerceAtLeast(1f)
    val (w, h) = fitSpan(cur.x, cur.y, width.coerceIn(1f, maxW), height.coerceAtLeast(1f), others)
    return applyPlacement(content, seeded, movedId) { it.copy(width = w, height = h) }
}

// Nearest free anchor by squared distance to the target, scanned in reading
// order. The empty row below everything always fits, so one is guaranteed.
private fun nearestFreeAnchor(
    col: Float,
    row: Float,
    w: Float,
    h: Float,
    cols: Int,
    others: List<Placement>,
): Pair<Float, Float> {
    fun free(c: Float, r: Float) = others.none { cellsOverlap(c, r, w, h, it) }
    if (free(col, row)) return col to row
    val maxRow = others.maxOfOrNull { (it.y + it.spanH()).toInt() } ?: 0
    var best = 0f to maxRow.toFloat()
    var bestD = Float.MAX_VALUE
    for (r in 0..maxRow) for (c in 0..(cols - w.toInt())) {
        if (!free(c.toFloat(), r.toFloat())) continue
        val d = (c - col) * (c - col) + (r - row) * (r - row)
        if (d < bestD) { bestD = d; best = c.toFloat() to r.toFloat() }
    }
    return best
}

// Largest span at or below the request that stays free at the fixed anchor,
// shrinking the larger dimension first. One by one is always free, since
// [others] excludes the widget itself.
private fun fitSpan(col: Float, row: Float, width: Float, height: Float, others: List<Placement>): Pair<Float, Float> {
    var w = width.coerceAtLeast(1f)
    var h = height.coerceAtLeast(1f)
    fun free() = others.none { cellsOverlap(col, row, w, h, it) }
    while ((w > 1f || h > 1f) && !free()) { if (w >= h) w-- else h-- }
    return w to h
}

// Writes one widget's placement through [transform], persisting the seeds the
// rest were just handed. Identity when no placement actually changed.
private fun applyPlacement(
    content: SlotContent,
    seeded: List<WidgetInstance>,
    movedId: String,
    transform: (Placement) -> Placement,
): SlotContent {
    val next = seeded.associate { w ->
        val base = w.placement ?: Placement()
        w.instanceId to if (w.instanceId == movedId) transform(base) else base
    }
    val changed = content.widgets.any { next[it.instanceId] != it.placement }
    return if (!changed) content
    else content.copy(widgets = content.widgets.map { w -> next[w.instanceId]?.let { w.copy(placement = it) } ?: w })
}

/** Lattice move addressed by path, for the editor. Identity when the instance is gone. */
fun LayoutGraph.placeWidgetInGrid(path: SlotPath, instanceId: String, target: Placement, columns: Int): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else placeInGrid(content, instanceId, target, columns)
    }

/** Lattice resize addressed by path, for the editor. */
fun LayoutGraph.resizeWidgetInGrid(path: SlotPath, instanceId: String, width: Float, height: Float, columns: Int): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else resizeInGrid(content, instanceId, width, height, columns)
    }

// ── Traversal ────────────────────────────────────────────────────────

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

// Rewrites every WidgetInstance graph-wide, replacing each with the 0..n
// instances `transform` returns (drop / keep / expand). A widget's own
// children are rewritten before the widget itself is handed to `transform`,
// so the transform always sees an already-converted subtree. Pure; the
// schema migrations use it to restructure widget kinds across the whole
// graph. The caller owns instanceId uniqueness across the produced set;
// load() sweeps the post-migration graph and falls back to the bundled
// default if a migration mints a collision.
fun LayoutGraph.flatMapInstances(
    transform: (WidgetInstance) -> List<WidgetInstance>,
): LayoutGraph = copy(
    surfaces = surfaces.mapValues { (_, layout) ->
        layout.copy(slots = layout.slots.mapValues { (_, content) -> content.flatMapInstances(transform) })
    },
)

private fun SlotContent.flatMapInstances(
    transform: (WidgetInstance) -> List<WidgetInstance>,
): SlotContent = copy(
    widgets = widgets.flatMap { w ->
        transform(w.copy(children = w.children.mapValues { (_, c) -> c.flatMapInstances(transform) }))
    },
)

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

// Restores `surface` to `defaultLayout` (its bundled default), first stripping
// any of the restored instanceIds that leaked onto OTHER surfaces (via a
// cross-surface move) so the tree-wide uniqueness invariant holds and the reset
// always succeeds -- otherwise the restored id collides with the leaked copy.
// A null defaultLayout (surface absent from the bundled default) removes the
// surface entirely. Pure so the escape-hatch path is unit-testable alongside
// the other LayoutGraph transforms.
fun LayoutGraph.resetSurface(surface: SurfaceId, defaultLayout: SurfaceLayout?): LayoutGraph {
    if (defaultLayout == null) return copy(surfaces = surfaces - surface)
    val restoredIds = defaultLayout.instanceIds()
    val cleaned = surfaces.mapValues { (sid, layout) ->
        if (sid == surface) layout else layout.removeInstanceIds(restoredIds)
    }
    return copy(surfaces = cleaned + (surface to defaultLayout))
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
