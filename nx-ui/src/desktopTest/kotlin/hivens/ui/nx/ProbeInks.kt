package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import hivens.look.colour.api.Paint
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk

/**
 * The colours the concept probes draw with, resolved once in composition so their draw
 * lambdas can read them by name.
 *
 * The probes are designs, kept as source so they can be re-rendered under any theme.
 * They draw on canvases, where a request cannot be made, so they take a snapshot of
 * the answers here instead.
 */
@Immutable
internal class ProbeInks(
    val lead: Color,
    val onLead: Color,
    val main: Color,
    val quiet: Color,
    /** The top authored step, what a floating plane is cut from. */
    val top: Color,
    /** The step below it. */
    val raised: Color,
    /** The lead colour washed into the plane, for a soft accent disc. */
    val leadWash: Color,
    /** The theme's third colour, for a second accent in a gradient. */
    val third: Color,
)

@Composable
internal fun probeInks(): ProbeInks {
    val lead = NxColor.lead()
    return ProbeInks(
        lead = lead,
        onLead = NxColor.on(lead),
        main = NxInk.main,
        quiet = NxInk.quiet,
        top = NxColor.depth(NxColor.floating),
        raised = NxColor.depth(NxColor.floating - 1),
        leadWash = NxColor.wash(lead, 0.3f),
        third = Paint.palette.getOrElse(2) { lead },
    )
}
