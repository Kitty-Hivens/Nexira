package hivens.widget.model

import kotlinx.serialization.Serializable

/**
 * How a slot arranges its children, when it arranges them at all.
 *
 * A slot is in one of two modes and the mode is which field is set, not a name
 * from a list. [SlotContent.flow] non-null means the slot derives each child's
 * position from the sequence. Null means each child carries its own position in
 * [WidgetInstance.placement].
 *
 * The five orientations this replaces were two ideas wearing five names. Column
 * and Row differed by one axis, and could not share a body only because
 * `Modifier.weight` is scope-typed in Compose, which is a fact about the toolkit
 * and not about the layout. Grid was the same flow with wrapping and equal
 * cells. Canvas and the cube grid were the same placement with and without
 * quantisation.
 *
 * [direction] is a string rather than an enum for the reason the whole format
 * gives: an enum on the wire read by a build that does not know the value has to
 * either throw or guess, and both are worse than falling back. Anything
 * unrecognised reads as vertical.
 */
@Serializable
data class FlowSpec(
    /** "vertical" or "horizontal". Anything else reads as vertical. */
    val direction: String = VERTICAL,
    /** Wrap after this many children. 0 never wraps, which is a plain row or column. */
    val wrap: Int = 0,
    /**
     * Equal cells across the wrap, rather than each child at its own size.
     * Meaningless without [wrap], and ignored there.
     */
    val uniform: Boolean = false,
) {
    val horizontal: Boolean get() = direction.trim().lowercase() == HORIZONTAL

    companion object {
        const val VERTICAL = "vertical"
        const val HORIZONTAL = "horizontal"

        /** A plain vertical stack, which is what a slot is when nothing says otherwise. */
        val Column = FlowSpec(VERTICAL)
        val Row = FlowSpec(HORIZONTAL)

        /** [columns] equal cells across, filled in order. */
        fun grid(columns: Int) = FlowSpec(HORIZONTAL, wrap = columns.coerceAtLeast(1), uniform = true)
    }
}

/**
 * Where one widget sits and how big it is.
 *
 * One record for both slot modes, because the fields a flow ignores are the ones
 * a placement needs and the other way round, and keeping them in separate
 * records is what produced three fields on the instance where at most one ever
 * meant anything.
 *
 * In a flow slot [weight] and the two sizes are read, as a share of the main
 * axis and as an upper bound. In a placement slot [anchor], the offset, the size
 * and [z] are read. Nothing is cleared when the slot changes mode: a slot
 * flipped and flipped back would otherwise cost the arrangement it had, from one
 * stray click.
 *
 * ## Units
 *
 * The offset and the size are in the unit the slot declares. [SlotContent.grid]
 * of 0 makes the unit one dp, which is free placement. A grid of N makes the
 * unit one cell of an N-column lattice, so the position survives a window
 * resize, which an absolute dp offset does not: an arrangement made wide is
 * clipped narrow, and one made narrow leaves dead margin wide.
 *
 * ## Anchor
 *
 * The offset is measured from [anchor], not from the top left. A widget parked
 * at the bottom right stays there when the window grows, which is the whole
 * reason a floating strip could never be expressed before. Unrecognised reads as
 * [TOP_START], so a corner a newer build knows degrades to the old meaning
 * rather than to nothing.
 */
@Serializable
data class Placement(
    val anchor: String = TOP_START,
    val x: Float = 0f,
    val y: Float = 0f,
    /** 0 means the widget's own size. */
    val width: Float = 0f,
    val height: Float = 0f,
    /** Paint order inside the slot. Higher draws in front. */
    val z: Int = 0,
    /** A share of a flow slot's main axis. 0 means natural size. Read only by a flow. */
    val weight: Float = 0f,
) {
    companion object {
        const val TOP_START = "topStart"
        const val TOP_CENTER = "topCenter"
        const val TOP_END = "topEnd"
        const val CENTER_START = "centerStart"
        const val CENTER = "center"
        const val CENTER_END = "centerEnd"
        const val BOTTOM_START = "bottomStart"
        const val BOTTOM_CENTER = "bottomCenter"
        const val BOTTOM_END = "bottomEnd"

        /** Every anchor this build answers, in reading order. */
        val ANCHORS: List<String> = listOf(
            TOP_START, TOP_CENTER, TOP_END,
            CENTER_START, CENTER, CENTER_END,
            BOTTOM_START, BOTTOM_CENTER, BOTTOM_END,
        )

        /** The default, which is also what an unrecognised value reads as. */
        val NONE = Placement()
    }
}

/**
 * The anchor [value] names, or [Placement.TOP_START] when it names none.
 *
 * Case and surrounding space are forgiven because this parses a field a person
 * may have typed into by hand, the same way the fill of a surface does.
 */
fun parseAnchor(value: String): String {
    val v = value.trim()
    if (v.isEmpty()) return Placement.TOP_START
    return Placement.ANCHORS.firstOrNull { it.equals(v, ignoreCase = true) } ?: Placement.TOP_START
}

/** Horizontal share of [parseAnchor]: 0 at the start edge, 0.5 centred, 1 at the end. */
fun anchorHorizontalBias(anchor: String): Float = when (parseAnchor(anchor)) {
    Placement.TOP_START, Placement.CENTER_START, Placement.BOTTOM_START -> 0f
    Placement.TOP_CENTER, Placement.CENTER, Placement.BOTTOM_CENTER -> 0.5f
    else -> 1f
}

/** Vertical share of [parseAnchor]: 0 at the top, 0.5 centred, 1 at the bottom. */
fun anchorVerticalBias(anchor: String): Float = when (parseAnchor(anchor)) {
    Placement.TOP_START, Placement.TOP_CENTER, Placement.TOP_END -> 0f
    Placement.CENTER_START, Placement.CENTER, Placement.CENTER_END -> 0.5f
    else -> 1f
}

/**
 * Quantises [value] to whole units when the slot declares a grid.
 *
 * A grid of 0 is free placement and returns the value untouched, so one call
 * site covers both modes and no caller has to branch on the mode to write a
 * position down.
 */
fun snapToGrid(value: Float, grid: Int): Float =
    if (grid <= 0) value else kotlin.math.round(value)
