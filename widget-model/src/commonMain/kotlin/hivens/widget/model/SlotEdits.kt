package hivens.widget.model

// Edits of one slot's content, whichever tree the slot is in: a layout graph's or a document's. Each returns
// the very object it was given where it changes nothing, which is what lets the tree above decide by identity
// that nothing changed, so an edit that changes nothing never hands back an equal copy.

/** [widget] put in at [index], held inside the bounds. */
fun SlotContent.withWidget(widget: WidgetInstance, index: Int): SlotContent =
    copy(widgets = widgets.toMutableList().apply { add(index.coerceIn(0, size), widget) })

/** Without the widget named [instanceId]. */
fun SlotContent.withoutWidget(instanceId: String): SlotContent =
    if (widgets.none { it.instanceId == instanceId }) this
    else copy(widgets = widgets.filterNot { it.instanceId == instanceId })

/** The widget at [fromIndex] moved to [toIndex]. */
fun SlotContent.reordered(fromIndex: Int, toIndex: Int): SlotContent {
    if (fromIndex !in widgets.indices) return this
    val target = toIndex.coerceIn(0, widgets.size - 1)
    if (fromIndex == target) return this
    return copy(widgets = widgets.toMutableList().apply { add(target, removeAt(fromIndex)) })
}

/** The widget named [instanceId] replaced by what [edit] makes of it. */
fun SlotContent.withWidgetUpdated(instanceId: String, edit: (WidgetInstance) -> WidgetInstance): SlotContent {
    val target = widgets.firstOrNull { it.instanceId == instanceId } ?: return this
    val next = edit(target)
    if (next == target) return this
    return copy(widgets = widgets.map { if (it.instanceId == instanceId) next else it })
}

/**
 * The widget's current placement (or the default when it carries none) through [edit], written back, so offset,
 * size, z, anchor and weight edits compose without clobbering one another mid-drag.
 */
fun SlotContent.withPlacement(instanceId: String, edit: (Placement) -> Placement): SlotContent =
    withWidgetUpdated(instanceId) { widget ->
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

/** The slot arranged by [flow], null putting it in placement mode with a position seeded onto every widget without one. */
fun SlotContent.withFlow(flow: FlowSpec?): SlotContent {
    if (this.flow == flow) return this
    if (flow != null) return copy(flow = flow)
    return copy(flow = null, widgets = seedPlacements(widgets, grid, latticeTransposed))
}

/** The slot measuring in [grid]: 0 for dp, N for an N-column lattice, clamped to [GRID_MAX]. */
fun SlotContent.withGrid(grid: Int): SlotContent {
    val coerced = grid.coerceIn(0, GRID_MAX)
    return if (this.grid == coerced) this else copy(grid = coerced)
}

/** The slot showing what does not fit by [viewport], a static record normalized to null. */
fun SlotContent.withViewport(viewport: ViewportSpec?): SlotContent {
    val normalized = viewport?.takeUnless { it.mode == ViewportMode.Static }
    if (this.viewport == normalized) return this
    var next = copy(viewport = normalized)
    val lattice = flow == null && grid > 0
    if (lattice && next.latticeTransposed != latticeTransposed) next = next.transposedPlacements()
    // A map holds placed widgets only, so a flow put on one becomes a placement
    // slot in the same step, seeded the way flipping it by hand would seed it.
    // The flow is kept as nothing rather than remembered: the two modes share
    // the slot and a placement slot is what a map is.
    if (next.viewportMode == ViewportMode.Map && next.flow != null) {
        next = next.copy(flow = null, widgets = seedPlacements(next.widgets, 0))
    }
    return next
}

/**
 * [widget] as it arrives in this slot. One arriving in a placement slot needs somewhere to be. It used to arrive
 * with whatever it carried from a flow slot, which is nothing, and then drew at the slot's origin on top of
 * whatever was already there. The seed is the same one a slot flipping into placement mode hands out, so a widget
 * that walks in and a widget that was already there are placed by one rule.
 */
internal fun SlotContent.seated(widget: WidgetInstance): WidgetInstance =
    if (flow == null && widget.placement == null) {
        widget.copy(placement = seedPlacement(widgets.size, grid, widgets, latticeTransposed))
    } else {
        widget
    }
