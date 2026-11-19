package hivens.look.shape.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Every answer of the shape axis, one per role a widget asks by. A widget reads them through
 * [hivens.look.shape.api.Shape]. A provider fills them in with [build].
 *
 * A role names a category every interface has, never one component: a component that keeps a corner
 * of its own beside its category's answer keeps it as its own. Each role answers any Compose [Shape],
 * so whether corners are round, cut, smoothed or something else entirely is the provider's to decide.
 *
 * A role a provider leaves unfilled keeps its base answer: square, the way Compose draws where nobody
 * chose a shape, with only the capsule fully round, since round ends are what makes one, and the
 * scrollbar thumb at Compose's own 4dp. [BASE] is every role at its base answer: a look with no
 * corners of its own. Every base answer is a corner shape, so the slots of a design system that
 * takes only those can be filled from it.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class ShapeAnswers private constructor(filled: Builder) {

    /** Something a person presses, types into or picks with: a button, a chip, a field, a select, a segment. */
    val control: Shape = filled.control ?: Neutral.SQUARE

    /** A region that holds a group or stands for one item in the flow: a card, a panel, a section, a banner. */
    val card: Shape = filled.card ?: Neutral.SQUARE

    /** Something whose ends are round by nature: a pill, a switch's track and knob, a badge, a round button. */
    val capsule: Shape = filled.capsule ?: Neutral.CAPSULE

    /** A mark saying where something is: a tab's underline, a selection bar. */
    val indicator: Shape = filled.indicator ?: Neutral.SQUARE

    /** A list floating off what opened it: a menu, a dropdown, a list of choices, a popover. */
    val menu: Shape = filled.menu ?: Neutral.SQUARE

    /** A short note floating beside what it explains. */
    val tooltip: Shape = filled.tooltip ?: Neutral.SQUARE

    /** A panel standing in from an edge of the window. */
    val sheet: Shape = filled.sheet ?: Neutral.SQUARE

    /** A window over the window, which asks something before anything else goes on. */
    val dialog: Shape = filled.dialog ?: Neutral.SQUARE

    /** The grip of a scrollbar, which shows where the view is and is dragged to move it. */
    val thumb: Shape = filled.thumb ?: Neutral.THUMB

    /**
     * The outline a widget's own plate takes where the widget's node leaves it unsaid: its corners, how
     * smoothly they meet the straight edge, and how round the points of a star or a polygon are. A
     * value set on the node is stronger. Base no rounding at all.
     */
    val plate: PlateOutline = filled.plate ?: Neutral.PLATE

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [ShapeAnswers].
     */
    class Builder internal constructor() {
        /** See [ShapeAnswers.control]. */
        var control: Shape? = null

        /** See [ShapeAnswers.card]. */
        var card: Shape? = null

        /** See [ShapeAnswers.capsule]. */
        var capsule: Shape? = null

        /** See [ShapeAnswers.indicator]. */
        var indicator: Shape? = null

        /** See [ShapeAnswers.menu]. */
        var menu: Shape? = null

        /** See [ShapeAnswers.tooltip]. */
        var tooltip: Shape? = null

        /** See [ShapeAnswers.sheet]. */
        var sheet: Shape? = null

        /** See [ShapeAnswers.dialog]. */
        var dialog: Shape? = null

        /** See [ShapeAnswers.thumb]. */
        var thumb: Shape? = null

        /** See [ShapeAnswers.plate]. */
        var plate: PlateOutline? = null
    }

    companion object {
        /** Every role at its base answer: the shape axis where no provider answers it. */
        val BASE: ShapeAnswers = ShapeAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): ShapeAnswers = ShapeAnswers(Builder().apply(block))
    }
}

/**
 * How a widget's own plate is outlined where its node says nothing: the radius of each corner
 * ([corner]), how far a corner eases into the straight edge rather than meeting it at a tangent
 * ([smoothing], 0 to 1), and how round the points of a star or a polygon are, as a share of its reach
 * ([pointRounding], 0 to 1). The kind of outline, and a star's points, depth and a polygon's sides,
 * are the plate's own and not asked here.
 */
@Immutable
class PlateOutline(
    val corner: Dp,
    val smoothing: Float,
    val pointRounding: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is PlateOutline && other.corner == corner && other.smoothing == smoothing && other.pointRounding == pointRounding

    override fun hashCode(): Int = listOf(corner, smoothing, pointRounding).hashCode()

    override fun toString(): String = "PlateOutline(corner=$corner, smoothing=$smoothing, pointRounding=$pointRounding)"
}
