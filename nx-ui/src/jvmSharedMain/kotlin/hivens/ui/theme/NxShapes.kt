package hivens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import hivens.look.shape.api.Shape
import hivens.look.shape.api.ShapeAnswers

/** How the shape axis outlines a widget's plate, see [hivens.look.shape.api.PlateOutline]. */
typealias PlateOutline = hivens.look.shape.api.PlateOutline

/**
 * The plate outline the shape axis answers here: what a widget's own plate takes where its node
 * leaves a field unsaid. See [hivens.look.shape.api.ShapeAnswers.plate].
 */
val plateOutline: PlateOutline
    @Composable @ReadOnlyComposable
    get() = Shape.plate

/**
 * nx's corners as the shape axis's answers: what the nx shape provider answers, and what [NxThemed]
 * draws previews and tests in. Made once, so a role is the same value wherever it is read.
 */
fun nxShapeAnswers(): ShapeAnswers = NxShape.ANSWERS

/**
 * The nx corners. A control is cut tighter than the card it sits on, a menu rounds as a card does, a
 * sheet and a dialog round more than anything in the flow, and a tooltip least of all. Corners do not
 * grow with what they round.
 */
private object NxShape {
    val ANSWERS: ShapeAnswers = ShapeAnswers.build {
        control = RoundedCornerShape(8.dp)
        card = RoundedCornerShape(12.dp)
        capsule = RoundedCornerShape(50)
        indicator = RectangleShape
        menu = RoundedCornerShape(12.dp)
        tooltip = RoundedCornerShape(4.dp)
        sheet = RoundedCornerShape(16.dp)
        dialog = RoundedCornerShape(24.dp)
        // The scrollbar agrees with the controls beside it.
        thumb = RoundedCornerShape(8.dp)
        // A widget's plate rounds like a card, as a literal of its own rather than the card's answer.
        plate = PlateOutline(corner = 12.dp, smoothing = 0f, pointRounding = 0f)
    }
}
