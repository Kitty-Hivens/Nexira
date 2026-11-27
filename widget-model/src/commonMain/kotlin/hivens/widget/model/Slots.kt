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
    // How this instance arrives when its surface opens, over its widget's
    // declaration. Null says nothing, which is every instance until somebody
    // changes it in the editor.
    val motion: WidgetMotion? = null,
    /**
     * Where this instance is shown, everywhere where null: the ids of the places the composition stands at, its config
     * and the screen its pages show. A widget of the frame stands in every config the way a bar stands on every
     * workspace, and what shows in it follows what the composition shows.
     */
    @SerialName("shown_on") val shownOn: List<String>? = null,
    /** Where this instance is hidden, whatever [shownOn] says. */
    @SerialName("hidden_on") val hiddenOn: List<String> = emptyList(),
) {
    /** Whether this instance is shown while the composition stands at [places]. */
    fun shownAt(places: Set<String>): Boolean = (shownOn == null || shownOn.any { it in places }) && hiddenOn.none { it in places }

    /** Whether this instance says where it is shown at all. */
    val conditional: Boolean get() = shownOn != null || hiddenOn.isNotEmpty()
}

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
 *
 * [viewport] is how the slot shows what does not fit in it, independent of the
 * arrangement: a flow and a placement slot can each be static or scroll. Null is
 * static.
 */
@Serializable
data class SlotContent(
    val widgets: List<WidgetInstance> = emptyList(),
    val flow: FlowSpec? = FlowSpec.Column,
    val grid: Int = 0,
    val viewport: ViewportSpec? = null,
)

/** Upper bound for [SlotContent.grid] and for [FlowSpec.wrap]; the steppers clamp to it. */
const val GRID_MAX = 48

// ── Seeding ──────────────────────────────────────────────────────────

/**
 * A position for the widget at [index] in a slot that measures in [grid].
 *
 * Free placement staggers into rows of three so a fresh set does not pile at the
 * origin. A lattice takes the first free cell in reading order, so widgets land
 * the way they read. Pure and deterministic, so the cascade is unit-testable.
 */
fun seedPlacement(
    index: Int,
    grid: Int,
    existing: List<WidgetInstance> = emptyList(),
    transposed: Boolean = false,
): Placement {
    // A lattice counting rows takes the first free cell down its first column,
    // which is reading order with the axes swapped.
    if (transposed && grid > 0) {
        val flipped = existing.map { w -> w.placement?.let { w.copy(placement = it.transposed()) } ?: w }
        return seedPlacement(index, grid, flipped).transposed()
    }
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
fun seedPlacements(widgets: List<WidgetInstance>, grid: Int, transposed: Boolean = false): List<WidgetInstance> {
    if (widgets.none { it.placement == null }) return widgets
    val settled = widgets.filter { it.placement != null }.toMutableList()
    return widgets.mapIndexed { index, w ->
        if (w.placement != null) return@mapIndexed w
        val seeded = w.copy(placement = seedPlacement(index, grid, settled, transposed))
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
fun placeInGrid(content: SlotContent, movedId: String, target: Placement, columns: Int): SlotContent =
    acrossLattice(content) { across, flip -> placeAcross(across, movedId, if (flip) target.transposed() else target, columns) }

private fun placeAcross(content: SlotContent, movedId: String, target: Placement, columns: Int): SlotContent {
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
fun resizeInGrid(content: SlotContent, movedId: String, width: Float, height: Float, columns: Int): SlotContent =
    acrossLattice(content) { across, flip ->
        if (flip) resizeAcross(across, movedId, height, width, columns) else resizeAcross(across, movedId, width, height, columns)
    }

/**
 * Runs a lattice rule written for columns on [content], swapping the axes first
 * and back afterwards when the lattice counts rows. Identity is kept: a rule that
 * moved nothing hands back the very object it was given, and so does this.
 */
private fun acrossLattice(content: SlotContent, rule: (SlotContent, Boolean) -> SlotContent): SlotContent {
    if (!content.latticeTransposed) return rule(content, false)
    val flipped = content.transposedPlacements()
    val out = rule(flipped, true)
    return if (out === flipped) content else out.transposedPlacements()
}

private fun resizeAcross(content: SlotContent, movedId: String, width: Float, height: Float, columns: Int): SlotContent {
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
