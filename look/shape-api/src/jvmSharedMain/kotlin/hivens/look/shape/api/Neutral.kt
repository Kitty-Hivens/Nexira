package hivens.look.shape.api

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The outlines of the base answers: what Compose itself draws where nobody chose a shape, so a window
 * drawn with no shape provider has no corners of any look in particular. Square, held as a corner
 * shape of no radius rather than as Compose's rectangle so a design system that takes only corner
 * shapes can take it.
 */
internal object Neutral {
    /** Every role with corners: none. */
    val SQUARE = RoundedCornerShape(0.dp)

    /** A capsule is fully round, half its shorter side at each end. */
    val CAPSULE = RoundedCornerShape(50)

    /** The scrollbar thumb Compose draws by default. */
    val THUMB = RoundedCornerShape(4.dp)

    /** A plate with no rounding at all. */
    val PLATE = PlateOutline(corner = 0.dp, smoothing = 0f, pointRounding = 0f)
}
