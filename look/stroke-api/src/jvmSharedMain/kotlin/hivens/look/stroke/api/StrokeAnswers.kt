package hivens.look.stroke.api

import androidx.compose.runtime.Immutable

/**
 * Every answer of the stroke axis, one per role a widget asks by. A widget reads them through [Stroke].
 * A provider fills them in with [build].
 *
 * A role names what a stroke is for, a category every interface has, never one component: a component
 * that keeps a stroke of its own beside its category's answer keeps it as its own. Each role answers a
 * [StrokeRole], which draws itself in the state the widget asks it in, so its width, ends, joins, dash
 * and anything beyond them are the provider's to decide. The colour is the colour axis's.
 *
 * A role a provider leaves unfilled keeps its base answer: a hairline, one device pixel with butt ends
 * and no dash, the way Compose strokes where nobody chose a width. [BASE] is every role at its base
 * answer: a look with no strokes of its own.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class StrokeAnswers private constructor(filled: Builder) {

    /**
     * The edge of something a person presses, types into or picks: an outlined button, a field, a select,
     * a tag. The edge of a plane, a card or a panel, is the plane's own material and not asked here.
     */
    val edge: StrokeRole = filled.edge ?: Neutral.HAIRLINE

    /** A mark saying where something is or which is chosen: a tab's underline, a chosen outline, an insertion bar. */
    val mark: StrokeRole = filled.mark ?: Neutral.HAIRLINE

    /** A rule between two groups or two rows. */
    val divider: StrokeRole = filled.divider ?: Neutral.HAIRLINE

    /** The outline of a place that is empty or can take something: an empty slot, a drop target. */
    val placeholder: StrokeRole = filled.placeholder ?: Neutral.HAIRLINE

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [StrokeAnswers].
     */
    class Builder internal constructor() {
        /** See [StrokeAnswers.edge]. */
        var edge: StrokeRole? = null

        /** See [StrokeAnswers.mark]. */
        var mark: StrokeRole? = null

        /** See [StrokeAnswers.divider]. */
        var divider: StrokeRole? = null

        /** See [StrokeAnswers.placeholder]. */
        var placeholder: StrokeRole? = null
    }

    companion object {
        /** Every role at its base answer: the stroke axis where no provider answers it. */
        val BASE: StrokeAnswers = StrokeAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): StrokeAnswers = StrokeAnswers(Builder().apply(block))
    }
}
