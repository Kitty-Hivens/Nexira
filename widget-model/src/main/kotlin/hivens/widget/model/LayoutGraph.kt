package hivens.widget.model

import kotlinx.serialization.KSerializer
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
    // Absolute placement when the enclosing slot is Canvas. Null for flow
    // slots (Column/Row/Grid) -- back-compat default for old layouts.
    val canvas: CanvasPlacement? = null,
    // Cell placement when the enclosing slot is CubeGrid (col/row + span in cells).
    // Null for non-grid slots -- back-compat default for old layouts.
    val cell: GridCell? = null,
    // Per-instance backing the kernel paints around the widget (glass card,
    // corner, padding). Null = no backing -- back-compat default for old
    // layouts. The editor's "Backing" section sets it on ANY widget, propless
    // included. Rendered in production via LocalWidgetChromeRenderer.
    val chrome: WidgetChrome? = null,
)

// Optional backing painted around a widget by the kernel: a glass card behind
// it ([glassAlphaPct] 0 = none), rounded corners ([cornerRadiusDp]), and inner
// padding. [paddingDp] is the uniform baseline; each side may override it via
// [paddingTopDp] / [paddingEndDp] / [paddingBottomDp] / [paddingStartDp], where
// -1 means "inherit the uniform value". Compose-free (widget-model carries no
// Compose); the kernel turns these scalars into a Modifier via the injected
// LocalWidgetChromeRenderer.
@Serializable
data class WidgetChrome(
    val glassAlphaPct: Int = 0,
    val cornerRadiusDp: Int = 0,
    val paddingDp: Int = 0,
    val paddingTopDp: Int = -1,
    val paddingEndDp: Int = -1,
    val paddingBottomDp: Int = -1,
    val paddingStartDp: Int = -1,
) {
    val effectiveTop: Int    get() = if (paddingTopDp    >= 0) paddingTopDp    else paddingDp
    val effectiveEnd: Int    get() = if (paddingEndDp    >= 0) paddingEndDp    else paddingDp
    val effectiveBottom: Int get() = if (paddingBottomDp >= 0) paddingBottomDp else paddingDp
    val effectiveStart: Int  get() = if (paddingStartDp  >= 0) paddingStartDp  else paddingDp
}

// Phase G: how a slot arranges its widgets. Column (default) reproduces
// the pre-Phase-G vertical stack; Row lays them horizontally; Grid flows
// them into `gridColumns` uniform cells (order-driven); Canvas places each
// widget at an absolute offset + size; CubeGrid places each widget at an
// addressed cell rectangle (col/row + span) and packs the slot on move/resize.
// Unknown is the forward-compat sentinel: a newer build's orientation read here
// folds to Unknown (never emitted intentionally) and renders as Column, rather
// than silently coercing to Column and discarding the real value's identity.
@Serializable
enum class SlotOrientation { Column, Row, Grid, Canvas, CubeGrid, Unknown }

/** Persistence codec that folds an unknown wire orientation to [SlotOrientation.Unknown]. */
object SlotOrientationSerializer : KSerializer<SlotOrientation> by LenientEnumSerializer(
    SlotOrientation.entries.toTypedArray(),
    SlotOrientation.Unknown,
    SlotOrientation.serializer(),
)

// Absolute placement of a widget inside a Canvas slot. x/y are dp offsets
// from the slot's top-left; width/height 0 means intrinsic/wrap (dp when set);
// z is the paint order (higher renders in front). Used only on Canvas slots.
@Serializable
data class CanvasPlacement(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val z: Int = 0,
)

// Cell placement of a widget inside a CubeGrid slot: 0-based column/row from the
// slot's top-left and a span in cells; z is the paint/stack order. Integer cells
// (vs CanvasPlacement's dp) -- a CubeGrid snaps to whole cells.
@Serializable
data class GridCell(
    val col: Int = 0,
    val row: Int = 0,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val z: Int = 0,
)

@Serializable
data class SlotContent(
    val widgets: List<WidgetInstance> = emptyList(),
    @Serializable(with = SlotOrientationSerializer::class)
    val orientation: SlotOrientation = SlotOrientation.Column,
    // Column count when orientation == Grid; ignored otherwise.
    val gridColumns: Int = 2,
)

/** Upper bound for [SlotContent.gridColumns]; the grid-column stepper clamps to it. */
const val GRID_COLUMNS_MAX = 12

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
): LayoutGraph = updateInstance(path, instanceId) { it.copy(props = props) }

// Sets (or clears, with null) the per-instance backing chrome. Same no-op /
// missing-instance contract as updateWidgetProps. A no-backing chrome
// normalizes to null so the field stays absent for default-styled widgets.
fun LayoutGraph.updateWidgetChrome(
    path: SlotPath,
    instanceId: String,
    chrome: WidgetChrome?,
): LayoutGraph {
    val normalized = chrome?.takeUnless { it == WidgetChrome() }
    return updateInstance(path, instanceId) { it.copy(chrome = normalized) }
}

// Every per-instance transform is the same three steps: find the instance in the
// slot, leave the graph alone when [edit] returns what was already there, and
// otherwise rebuild the list with that one widget replaced. Written out five
// times, it was five chances for the no-op contract to be spelled differently --
// and one of them did not check at all.
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

// Phase G layout transforms. Slot orientation + grid column count are
// slot-level; widget weight is per-instance. Each is a no-op (identity
// return) when the value is unchanged or the slot/instance is missing --
// same contract as the transforms above.
fun LayoutGraph.setSlotOrientation(path: SlotPath, orientation: SlotOrientation): LayoutGraph =
    mutate(path) { content ->
        if (content.orientation == orientation) return@mutate content
        when (orientation) {
            // Flipping to Canvas: seed a staggered grid onto widgets with no
            // placement yet, so they don't all pile at (0,0). Already-placed
            // widgets keep their placement -- re-entering Canvas is idempotent.
            SlotOrientation.Canvas -> content.copy(
                orientation = SlotOrientation.Canvas,
                widgets = content.widgets.mapIndexed { i, w ->
                    if (w.canvas != null) w else w.copy(canvas = seededCanvasPlacement(i))
                },
            )
            // Flipping to CubeGrid: seed 1x1 cells in flow order onto unplaced
            // widgets; already-placed widgets keep their cell.
            SlotOrientation.CubeGrid -> content.copy(
                orientation = SlotOrientation.CubeGrid,
                widgets = seededCubeGrid(content.widgets, content.gridColumns),
            )
            else -> content.copy(orientation = orientation)
        }
    }

// Staggered default placement for the Nth not-yet-placed widget when a slot
// flips to Canvas. width/height 0 keeps intrinsic size until the user resizes;
// z = index preserves the prior stacking order as the initial paint order.
// Pure + deterministic so the seed cascade is unit-testable.
fun seededCanvasPlacement(
    index: Int,
    columns: Int = 3,
    cellWidth: Float = 220f,
    cellHeight: Float = 160f,
    marginX: Float = 16f,
    marginY: Float = 16f,
): CanvasPlacement {
    val col = index % columns
    val row = index / columns
    return CanvasPlacement(
        x = marginX + col * cellWidth,
        y = marginY + row * cellHeight,
        z = index,
    )
}

// Seeds 1x1 cells in row-major flow onto widgets without one when a slot flips to
// CubeGrid; already-placed widgets keep their cell. Pure + deterministic.
fun seededCubeGrid(widgets: List<WidgetInstance>, columns: Int): List<WidgetInstance> {
    val cols = columns.coerceIn(1, GRID_COLUMNS_MAX)
    val taken = widgets.mapNotNull { it.cell }.toMutableList()
    fun occupied(c: Int, r: Int) = taken.any { g -> c >= g.col && c < g.col + g.colSpan && r >= g.row && r < g.row + g.rowSpan }
    var scan = 0
    return widgets.map { w ->
        if (w.cell != null) return@map w
        while (occupied(scan % cols, scan / cols)) scan++
        val cell = GridCell(col = scan % cols, row = scan / cols)
        scan++
        taken.add(cell)
        w.copy(cell = cell)
    }
}

fun LayoutGraph.setGridColumns(path: SlotPath, columns: Int): LayoutGraph =
    mutate(path) { content ->
        val coerced = columns.coerceIn(1, GRID_COLUMNS_MAX)
        if (content.gridColumns == coerced) content
        else content.copy(gridColumns = coerced)
    }

// CubeGrid (Android-launcher style): snap [instanceId]'s anchor to [target] keeping
// its span. No overlap, no compaction -- if the target footprint collides with another
// widget, snap to the nearest free anchor instead; other widgets never move and empty
// cells are allowed. Identity no-op when the instance is missing or nothing changes.
fun LayoutGraph.placeWidgetInCell(path: SlotPath, instanceId: String, target: GridCell, columns: Int): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else placeInCubeGrid(content, instanceId, target, columns)
    }

// CubeGrid resize: keep [instanceId]'s anchor, grow its span toward [colSpan]x[rowSpan]
// but clamp to the largest that stays free (no overlap, no teleport, other widgets fixed).
fun LayoutGraph.resizeWidgetInCell(path: SlotPath, instanceId: String, colSpan: Int, rowSpan: Int, columns: Int): LayoutGraph =
    mutate(path) { content ->
        if (content.widgets.none { it.instanceId == instanceId }) content
        else resizeInCubeGrid(content, instanceId, colSpan, rowSpan, columns)
    }

// Pure CubeGrid move: anchor [movedId] at the clamped [target], or -- if that footprint
// overlaps another widget -- at the nearest free cell. Other widgets are NEVER moved (no
// compaction; gaps are allowed). The point is a snap grid layered over a free canvas, not
// an auto-packer. Null-cell widgets are flow-seeded first. Compose-free + deterministic,
// so it is unit-testable. Returns the same content when no cell changes.
fun placeInCubeGrid(content: SlotContent, movedId: String, target: GridCell, columns: Int): SlotContent {
    val cols = columns.coerceIn(1, GRID_COLUMNS_MAX)
    val seeded = seededCubeGrid(content.widgets, cols)
    val cs = target.colSpan.coerceIn(1, cols)
    val rs = target.rowSpan.coerceAtLeast(1)
    val others = seeded.filter { it.instanceId != movedId }.mapNotNull { it.cell }
    val (col, row) = nearestFreeCubeAnchor(target.col.coerceIn(0, cols - cs), target.row.coerceAtLeast(0), cs, rs, cols, others)
    return applyCubeCell(content, seeded, movedId) { it.copy(col = col, row = row, colSpan = cs, rowSpan = rs) }
}

// Pure CubeGrid resize: clamp the requested span to the largest free rectangle anchored
// at [movedId]'s current cell. Other widgets are fixed (no overlap, no relocation).
fun resizeInCubeGrid(content: SlotContent, movedId: String, colSpan: Int, rowSpan: Int, columns: Int): SlotContent {
    val cols = columns.coerceIn(1, GRID_COLUMNS_MAX)
    val seeded = seededCubeGrid(content.widgets, cols)
    val cur = seeded.firstOrNull { it.instanceId == movedId }?.cell ?: GridCell()
    val others = seeded.filter { it.instanceId != movedId }.mapNotNull { it.cell }
    val maxCol = (cols - cur.col).coerceAtLeast(1)
    val (cs, rs) = fitCubeSpan(cur.col, cur.row, colSpan.coerceIn(1, maxCol), rowSpan.coerceAtLeast(1), others)
    return applyCubeCell(content, seeded, movedId) { it.copy(colSpan = cs, rowSpan = rs) }
}

private fun cubeOverlap(col: Int, row: Int, colSpan: Int, rowSpan: Int, b: GridCell): Boolean =
    col < b.col + b.colSpan && b.col < col + colSpan && row < b.row + b.rowSpan && b.row < row + rowSpan

// Nearest free anchor (squared distance to the target, row-major scan) for a
// colSpan x rowSpan footprint overlapping none of [others]. The empty row below
// everything always fits, so a free anchor is guaranteed.
private fun nearestFreeCubeAnchor(col: Int, row: Int, colSpan: Int, rowSpan: Int, cols: Int, others: List<GridCell>): Pair<Int, Int> {
    fun free(c: Int, r: Int) = others.none { cubeOverlap(c, r, colSpan, rowSpan, it) }
    if (free(col, row)) return col to row
    val maxRow = others.maxOfOrNull { it.row + it.rowSpan } ?: 0
    var best = 0 to maxRow
    var bestD = Int.MAX_VALUE
    for (r in 0..maxRow) for (c in 0..(cols - colSpan)) {
        if (!free(c, r)) continue
        val d = (c - col) * (c - col) + (r - row) * (r - row)
        if (d < bestD) { bestD = d; best = c to r }
    }
    return best
}

// Largest span <= requested that stays free at the fixed anchor (shrink the larger
// dimension first). (1,1) is always free since [others] excludes the widget itself.
private fun fitCubeSpan(col: Int, row: Int, colSpan: Int, rowSpan: Int, others: List<GridCell>): Pair<Int, Int> {
    var c = colSpan.coerceAtLeast(1)
    var r = rowSpan.coerceAtLeast(1)
    fun free() = others.none { cubeOverlap(col, row, c, r, it) }
    while ((c > 1 || r > 1) && !free()) { if (c >= r) c-- else r-- }
    return c to r
}

// Set one widget's cell via [transform], persisting the seeded cells of the rest.
private fun applyCubeCell(content: SlotContent, seeded: List<WidgetInstance>, movedId: String, transform: (GridCell) -> GridCell): SlotContent {
    val cells = seeded.associate { w ->
        val base = w.cell ?: GridCell()
        w.instanceId to if (w.instanceId == movedId) transform(base) else base
    }
    val changed = content.widgets.any { cells[it.instanceId] != it.cell }
    return if (!changed) content
    else content.copy(widgets = content.widgets.map { w -> cells[w.instanceId]?.let { w.copy(cell = it) } ?: w })
}

fun LayoutGraph.setWidgetWeight(path: SlotPath, instanceId: String, weight: Float): LayoutGraph =
    updateInstance(path, instanceId) { it.copy(weight = weight.coerceAtLeast(0f)) }

// Canvas placement transforms (used when the slot is Canvas). Same no-op /
// missing-instance identity contract as the Phase G transforms above.
fun LayoutGraph.setCanvasPlacement(path: SlotPath, instanceId: String, placement: CanvasPlacement): LayoutGraph =
    updateInstance(path, instanceId) { it.copy(canvas = placement) }

fun LayoutGraph.setWidgetOffset(path: SlotPath, instanceId: String, x: Float, y: Float): LayoutGraph =
    updateCanvas(path, instanceId) { it.copy(x = x, y = y) }

fun LayoutGraph.setWidgetSize(path: SlotPath, instanceId: String, width: Float, height: Float): LayoutGraph =
    updateCanvas(path, instanceId) { it.copy(width = width.coerceAtLeast(0f), height = height.coerceAtLeast(0f)) }

fun LayoutGraph.setWidgetZ(path: SlotPath, instanceId: String, z: Int): LayoutGraph =
    updateCanvas(path, instanceId) { it.copy(z = z) }

// Reads the widget's current placement (or the default when null), applies
// `edit`, and writes it back -- so offset / size / z edits compose without
// clobbering each other. No-op when the result is unchanged.
private fun LayoutGraph.updateCanvas(
    path: SlotPath,
    instanceId: String,
    edit: (CanvasPlacement) -> CanvasPlacement,
): LayoutGraph = updateInstance(path, instanceId) { it.copy(canvas = edit(it.canvas ?: CanvasPlacement())) }

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
