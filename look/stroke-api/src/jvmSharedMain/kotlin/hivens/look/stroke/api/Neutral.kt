package hivens.look.stroke.api

import androidx.compose.ui.unit.Dp

/**
 * The stroke of the base answers: what Compose itself draws where nobody chose a width, so a window
 * drawn with no stroke provider strokes as no look in particular. Compose's default stroke style is a
 * hairline, one device pixel at any density, with butt ends, mitred joins and no dash.
 */
internal object Neutral {
    /** Every role: a plain hairline. */
    val HAIRLINE: StrokeRole = StrokeRole.solid(Dp.Hairline)
}
