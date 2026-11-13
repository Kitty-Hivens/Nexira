package hivens.ui.layers

/**
 * What a window's layer host draws above its own content, bottom to top, and where
 * input stops.
 *
 * [order] is the stacking order with one change while a modal is up: the residents
 * under it that stay usable are lifted above the ones it blocks, so the gate that
 * blocks input can sit between the two groups. Input and drawing follow the same
 * order in Compose, and a usable control has to be both reachable and seen, so it
 * draws above a blocked resident it overlaps rather than under it. Everything at or
 * above [gate] takes input. Everything below it, the window's own content included,
 * does not.
 *
 * A resident's z is its index here, never its stamp.
 */
internal class LayerPlan(
    val order: List<LayerResident>,
    /** Index in [order] the input gate sits under, or -1 when no modal is up. */
    val gate: Int,
    /** The topmost modal, or null. */
    val modal: LayerResident?,
) {
    private val ranks: Map<LayerResident, Int> = order.withIndex().associate { (i, r) -> r to i }

    /** Where [resident] stands in [order], -1 for the window's own content or a resident not shown. */
    fun rankOf(resident: LayerResident?): Int = resident?.let { ranks[it] } ?: -1

    /** Whether input reaches what stands at [rank]. */
    fun takesInput(rank: Int): Boolean = gate < 0 || rank >= gate

    companion object {
        val Empty = LayerPlan(emptyList(), gate = -1, modal = null)
    }
}

/**
 * Lays out the shown residents.
 *
 * A resident opened from inside another is that one's child, and a parent and its
 * descendants stack as one family, the parent at the bottom of it. Among siblings
 * the family that appeared most recently is on top, counting every member: a
 * confirmation opened from a sheet brings the sheet up with it, over a menu shown
 * after the sheet but before the confirmation, so the newest thing on screen is
 * never under an older one. A pinned family stands above every unpinned sibling.
 *
 * Under the topmost modal, the families that stay usable are lifted above the
 * blocked ones whole, so a menu opened from a usable layer stays above it and usable
 * with it. A resident counts as usable when it or one of its ancestors is.
 */
internal fun planOf(shown: Collection<LayerResident>): LayerPlan {
    val stacked = stackingOrder(shown)
    val modalAt = stacked.indexOfLast { it.modal }
    if (modalAt < 0) return LayerPlan(stacked, gate = -1, modal = null)
    val present = stacked.toHashSet()
    val under = stacked.subList(0, modalAt)
    val usable = under.filter { it.usableIn(present) }
    val blocked = under.filterNot { it.usableIn(present) }
    return LayerPlan(
        order = blocked + usable + stacked.subList(modalAt, stacked.size),
        gate = blocked.size,
        modal = stacked[modalAt],
    )
}

private fun LayerResident.usableIn(present: Set<LayerResident>): Boolean {
    var at: LayerResident? = this
    while (at != null && at in present) {
        if (at.staysUsable) return true
        at = at.parent
    }
    return false
}

internal fun stackingOrder(shown: Collection<LayerResident>): List<LayerResident> {
    val present = shown.toHashSet()
    // A parent that is not shown is no parent: its child stands among the roots
    // rather than dropping out of the order.
    val children = shown.groupBy { r -> r.parent?.takeIf { it in present } }
    val latest = HashMap<LayerResident, Long>()
    fun latestIn(r: LayerResident): Long = latest.getOrPut(r) {
        maxOf(r.stamp, children[r]?.maxOfOrNull(::latestIn) ?: Long.MIN_VALUE)
    }
    val siblings = compareBy<LayerResident>({ it.pinnedTop }, { latestIn(it) }, { it.stamp })
    val out = ArrayList<LayerResident>(shown.size)
    fun visit(level: List<LayerResident>?) {
        level?.sortedWith(siblings)?.forEach {
            out += it
            visit(children[it])
        }
    }
    visit(children[null])
    return out
}
