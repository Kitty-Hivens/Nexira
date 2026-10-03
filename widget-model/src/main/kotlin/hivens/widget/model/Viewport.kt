package hivens.widget.model

import kotlinx.serialization.Serializable

/**
 * How a slot shows content that is larger than the room it was given.
 *
 * A slot used to answer this in the code of whichever surface declared it: one
 * wrapped its slot in a scroll, two refused to with a comment explaining why, and
 * nothing a person arranged could change the answer. So it is a property of the
 * slot now, beside the arrangement, and the kernel owns what each answer means
 * for the widgets inside.
 *
 * [kind] and [axis] are strings for the reason [FlowSpec.direction] is one: a build
 * that meets a kind it does not know falls back to [STATIC], which shows the
 * content in place and cuts what does not fit, instead of failing the file. That
 * fallback is the safe one because static is what every slot was before this
 * existed.
 *
 * Absent means static. The field is null on every slot nobody changed, so a file
 * only says something when somebody did.
 */
@Serializable
data class ViewportSpec(
    /** [STATIC], [SCROLL] or [MAP]. Anything else reads as static. */
    val kind: String = STATIC,
    /** [VERTICAL] or [HORIZONTAL], for a kind that moves along one axis. Anything else reads as vertical. */
    val axis: String = VERTICAL,
    /** Whether the slot draws a scrollbar along the axis it scrolls on. */
    val scrollbar: Boolean = true,
) {
    /** What this build makes of the record. */
    val mode: ViewportMode
        get() = when (kind.trim().lowercase()) {
            SCROLL -> ViewportMode.Scroll(horizontal = axis.trim().lowercase() == HORIZONTAL)
            MAP -> ViewportMode.Map
            else -> ViewportMode.Static
        }

    companion object {
        const val STATIC = "static"
        const val SCROLL = "scroll"
        const val MAP = "map"
        const val VERTICAL = "vertical"
        const val HORIZONTAL = "horizontal"

        /** A slot that scrolls down, the way a page does. */
        val ScrollDown = ViewportSpec(SCROLL, VERTICAL)

        /** A slot that scrolls sideways. */
        val ScrollRight = ViewportSpec(SCROLL, HORIZONTAL)

        /** A plane with no edges, moved on both axes. */
        val Map = ViewportSpec(MAP)
    }
}

/** A [ViewportSpec] as this build reads it. */
sealed interface ViewportMode {
    /** Bounded on both axes: the content is laid out in the room given and cut past it. */
    data object Static : ViewportMode

    /**
     * Unbounded along one axis and moved along it. [horizontal] false is down,
     * true is sideways.
     */
    data class Scroll(val horizontal: Boolean) : ViewportMode

    /**
     * Unbounded on both axes, a plane with no edges that the view is moved over.
     *
     * It holds placed widgets only, each at its own point on the plane, counted
     * from the plane's origin whatever corner it names: with no edges there is
     * no far corner to count from, and a lattice has no width to divide.
     */
    data object Map : ViewportMode
}

/** How this slot shows what does not fit, static when nothing has said. */
val SlotContent.viewportMode: ViewportMode get() = viewport?.mode ?: ViewportMode.Static

/**
 * Whether this slot's lattice counts rows rather than columns.
 *
 * A lattice counts its lines across the side that does not move, which in a slot
 * scrolling sideways is the height. Every lattice rule is written for columns, so
 * a transposed slot runs them on its placements with the two axes swapped.
 */
val SlotContent.latticeTransposed: Boolean
    get() = (viewportMode as? ViewportMode.Scroll)?.horizontal == true

/** This placement with its two axes swapped. */
internal fun Placement.transposed(): Placement = copy(x = y, y = x, width = height, height = width)

/** Every placement in the slot with its two axes swapped. Twice is the identity. */
internal fun SlotContent.transposedPlacements(): SlotContent =
    copy(widgets = widgets.map { w -> w.placement?.let { w.copy(placement = it.transposed()) } ?: w })
