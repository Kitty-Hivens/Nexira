package hivens.ui.theme

import androidx.compose.ui.unit.dp
import hivens.look.stroke.api.Dash
import hivens.look.stroke.api.StrokeAnswers
import hivens.look.stroke.api.StrokeRole

/**
 * nx's strokes as the stroke axis's answers: what the nx stroke provider answers, and what [NxThemed]
 * draws previews and tests in. Made once, so a role is the same value wherever it is read.
 */
fun nxStrokeAnswers(): StrokeAnswers = NxStroke.ANSWERS

/**
 * The nx strokes. Edges and rules are a single dp, a mark that says where something is doubles that so
 * it reads as a mark and not as one more edge, and an empty place is dashed a little heavier than an
 * edge so it reads at a glance on any plane. The same in every state: nx answers a state by colour.
 */
private object NxStroke {
    val ANSWERS: StrokeAnswers = StrokeAnswers.build {
        edge = StrokeRole.solid(1.dp)
        mark = StrokeRole.solid(2.dp)
        divider = StrokeRole.solid(1.dp)
        placeholder = StrokeRole.solid(1.5.dp, dash = Dash(8.dp, 6.dp))
    }
}
