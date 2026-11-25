package hivens.look.feedback.nx

import hivens.look.feedback.api.FeedbackAnswers
import hivens.look.feedback.api.FeedbackTarget

/**
 * nx's feedback as the feedback axis's answers: what the nx feedback provider answers, and what
 * previews and tests draw in. Made once, so a role is the same value wherever it is read.
 */
fun nxFeedbackAnswers(): FeedbackAnswers = NxFeedback.ANSWERS

/**
 * The nx feedback. A control answers with a state layer of its own content colour on the M3 scale,
 * hover 8, focus 10, press 12 percent, at its own outline. A plane answers with a lighter wash of the
 * colour it is given, hover 6 and press 12 percent, and none for focus. Disabled content stands at 45
 * percent, the same as the quiet ink's disabled form.
 */
private object NxFeedback {
    val ANSWERS: FeedbackAnswers = FeedbackAnswers.build {
        indication = { target, shape, tint ->
            when (target) {
                FeedbackTarget.Control -> StateLayer(shape, tint, StateLayerAlphas.CONTROL)
                FeedbackTarget.Plane -> StateLayer(shape, tint, StateLayerAlphas.PLANE)
            }
        }
        disabledAlpha = 0.45f
    }
}
