package hivens.look.timing.nx

import hivens.look.timing.api.TimingAnswers
import hivens.look.timing.api.TooltipKind

/**
 * nx's waits as the timing axis's answers: what the nx timing provider answers, and what previews
 * and tests draw in. Made once, so a role is the same value wherever it is read.
 */
fun nxTimingAnswers(): TimingAnswers = NxTiming.ANSWERS

/**
 * The nx waits. A label appears fast, an explanation waits longer, a picture longer still, as each
 * asks more of the reader.
 */
private object NxTiming {
    val ANSWERS: TimingAnswers = TimingAnswers.build {
        tooltipDelay = { kind ->
            when (kind) {
                TooltipKind.Label -> 400
                TooltipKind.Explain -> 600
                TooltipKind.Preview -> 700
            }
        }
    }
}
