package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.shape.api.Shape

/** How the shape axis outlines a widget's plate, see [hivens.look.shape.api.PlateOutline]. */
typealias PlateOutline = hivens.look.shape.api.PlateOutline

/**
 * The plate outline the shape axis answers here: what a widget's own plate takes where its node
 * leaves a field unsaid. See [hivens.look.shape.api.ShapeAnswers.plate].
 */
val plateOutline: PlateOutline
    @Composable @ReadOnlyComposable
    get() = Shape.plate
