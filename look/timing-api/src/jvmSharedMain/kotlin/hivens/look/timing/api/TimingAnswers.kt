package hivens.look.timing.api

import androidx.compose.runtime.Immutable

/** What a tooltip is for, which decides how long it waits before it appears. */
enum class TooltipKind {
    /** Names the thing under the pointer, in a line. */
    Label,

    /** Explains it, in a few lines that are read. */
    Explain,

    /** Shows it, a picture of what it stands for. */
    Preview,
}

/**
 * Every answer of the timing axis. A widget reads them through [Timing]. A provider fills them in with
 * [build].
 *
 * A role a provider leaves unfilled keeps its base answer: the wait Compose itself gives a tooltip
 * where nobody chose one, half a second. [BASE] is every role at its base answer: a look with no waits
 * of its own.
 *
 * Nothing here moves a layout. How a piece behaves once it shows, whether it can be entered, when it
 * leaves, is the component's own.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class TimingAnswers private constructor(filled: Builder) {

    private val tooltipRule = filled.tooltipDelay

    /** How long a tooltip of [kind] waits under the pointer before it appears, in milliseconds. Base 500. */
    fun tooltipDelay(kind: TooltipKind): Int = tooltipRule?.invoke(kind) ?: Neutral.TOOLTIP_DELAY_MS

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [TimingAnswers].
     */
    class Builder internal constructor() {
        /** See [TimingAnswers.tooltipDelay]. */
        var tooltipDelay: ((kind: TooltipKind) -> Int)? = null
    }

    companion object {
        /** Every role at its base answer: the timing axis where no provider answers it. */
        val BASE: TimingAnswers = TimingAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): TimingAnswers = TimingAnswers(Builder().apply(block))
    }
}

/** The waits of the base answers: Compose's own. */
internal object Neutral {
    /** The delay Compose's desktop `TooltipArea` waits where none is given. */
    const val TOOLTIP_DELAY_MS = 500
}
