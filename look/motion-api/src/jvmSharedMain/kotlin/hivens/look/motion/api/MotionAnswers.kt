package hivens.look.motion.api

import androidx.compose.runtime.Immutable
import hivens.widget.model.Entrance

/**
 * Every answer of the motion axis, one per role a widget asks by. A widget reads them through [Motion].
 * A provider fills them in with [build].
 *
 * A role a provider leaves unfilled keeps its base answer: Compose's own tween, 300 milliseconds on its
 * standard curve, coming and going on a fade, the movement Compose makes where nobody chose one. Loops
 * are linear. [BASE] is every role at its base answer: a look that moves plainly and holds nothing
 * still.
 *
 * Some movement only dresses a change that is already made. A provider may hold it still, and answers
 * null for it then: [decoration], [lookChange], [drift], and a stagger of nothing. Every other role always
 * moves, since what it says would be lost without it.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class MotionAnswers private constructor(filled: Builder) {

    /** A press, a hover, a thumb sliding: the interface answering a finger, short enough to read as immediate. */
    val tap: MotionRole = filled.tap ?: Neutral.ROLE

    /** Colour settling into a new state: a selection, a validity, a fill. */
    val colourShift: MotionRole = filled.colourShift ?: Neutral.ROLE

    /** A mark travelling to what it now marks: a tab's underline, a thumb, a known share of work. */
    val track: MotionRole = filled.track ?: Neutral.ROLE

    /** Something appearing or leaving on opacity alone, and a crossfade. */
    val fade: MotionRole = filled.fade ?: Neutral.ROLE

    /** A menu or a list opening from what opened it, its [MotionRole.enter] and [MotionRole.exit] about that origin. */
    val popup: MotionRole = filled.popup ?: Neutral.ROLE

    /** A panel, a rail or a sheet travelling in from its edge. */
    val panelSlide: MotionRole = filled.panelSlide ?: Neutral.ROLE

    /** Content opening or folding away: an expanding row, a growing card, a size settling. */
    val reveal: MotionRole = filled.reveal ?: Neutral.ROLE

    /** Something landing and claiming attention: a dialog, a face joining a stack. */
    val emphasis: MotionRole = filled.emphasis ?: Neutral.ROLE

    /** One page or view giving way to another in the same place. */
    val pageSwap: MotionRole = filled.pageSwap ?: Neutral.ROLE

    /** A view scrolled by the interface rather than by a finger: to a tab, to an item. */
    val scroll: MotionRole = filled.scroll ?: Neutral.ROLE

    /** Something swiped away, or settling back where the swipe was not far enough. */
    val dismiss: MotionRole = filled.dismiss ?: Neutral.ROLE

    /** The look itself changing, a switch of day and night say. Null where the look holds it still: the change is made at once. */
    val lookChange: MotionRole? = filled.lookChange.orBase()

    /**
     * Ambient movement nothing waits on: drifting particles, a slow spin, an effect over the whole
     * interface. Null where the look holds it still, and the movement then stays on its first frame.
     * Whoever moves times its own movement, and reads this for whether it moves.
     */
    val drift: MotionRole? = filled.drift.orBase()

    /** Continuous movement with no start or end, a spinner, an indeterminate bar, a placeholder's sheen. Base 1000 ms, linear. */
    val sweep: MotionLoop = filled.sweep ?: Neutral.SWEEP

    private val ambientRule = filled.ambient
    private val arrivalRule = filled.arrival
    private val staggerRule = filled.stagger
    private val decorationRule = filled.decoration

    /**
     * A decorative loop whose period belongs to the effect, [periodMs], a glow breathing say. The look
     * answers how one period runs. Base the period, linear.
     */
    fun ambient(periodMs: Int): MotionLoop = ambientRule?.invoke(periodMs) ?: MotionLoop.tween(periodMs, Neutral.LOOP_EASING)

    /**
     * What a widget's arrival [entrance] means on this look. Null for [Entrance.None], which arrives by
     * nothing. Base a plain fade on the base role for every other.
     */
    fun arrival(entrance: Entrance): Arrival? =
        if (entrance == Entrance.None) null else arrivalRule?.invoke(entrance) ?: Neutral.ARRIVAL

    /**
     * How long the widget at [order] in its slot waits before it arrives, where it pins no delay of its
     * own, so a surface assembles rather than appears. Zero where the look holds the order still. Base
     * zero: siblings arrive together.
     */
    fun stagger(order: Int): Int = staggerRule?.invoke(order) ?: 0

    /**
     * [role] when the movement only decorates a change already made: a row's hover wash, a height
     * settling, a sheet travelling in. Null where the look holds such movement still, and the change is
     * then drawn at once. Base [role].
     */
    fun decoration(role: MotionRole): MotionRole? {
        val rule = decorationRule ?: return role
        return rule(role)
    }

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [MotionAnswers]. A role that may be held still is
     * filled with how to answer it, returning null to hold it still.
     */
    class Builder internal constructor() {
        /** See [MotionAnswers.tap]. */
        var tap: MotionRole? = null

        /** See [MotionAnswers.colourShift]. */
        var colourShift: MotionRole? = null

        /** See [MotionAnswers.track]. */
        var track: MotionRole? = null

        /** See [MotionAnswers.fade]. */
        var fade: MotionRole? = null

        /** See [MotionAnswers.popup]. */
        var popup: MotionRole? = null

        /** See [MotionAnswers.panelSlide]. */
        var panelSlide: MotionRole? = null

        /** See [MotionAnswers.reveal]. */
        var reveal: MotionRole? = null

        /** See [MotionAnswers.emphasis]. */
        var emphasis: MotionRole? = null

        /** See [MotionAnswers.pageSwap]. */
        var pageSwap: MotionRole? = null

        /** See [MotionAnswers.scroll]. */
        var scroll: MotionRole? = null

        /** See [MotionAnswers.dismiss]. */
        var dismiss: MotionRole? = null

        /** See [MotionAnswers.lookChange]. */
        var lookChange: (() -> MotionRole?)? = null

        /** See [MotionAnswers.drift]. */
        var drift: (() -> MotionRole?)? = null

        /** See [MotionAnswers.sweep]. */
        var sweep: MotionLoop? = null

        /** See [MotionAnswers.ambient]. */
        var ambient: ((periodMs: Int) -> MotionLoop)? = null

        /** See [MotionAnswers.arrival]. Never asked for [Entrance.None]. */
        var arrival: ((entrance: Entrance) -> Arrival)? = null

        /** See [MotionAnswers.stagger]. */
        var stagger: ((order: Int) -> Int)? = null

        /** See [MotionAnswers.decoration]. */
        var decoration: ((role: MotionRole) -> MotionRole?)? = null
    }

    companion object {
        /** Every role at its base answer: the motion axis where no provider answers it. */
        val BASE: MotionAnswers = MotionAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): MotionAnswers = MotionAnswers(Builder().apply(block))

        private fun (() -> MotionRole?)?.orBase(): MotionRole? = if (this == null) Neutral.ROLE else this()
    }
}
