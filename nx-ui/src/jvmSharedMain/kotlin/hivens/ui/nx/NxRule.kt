package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeState
import hivens.look.stroke.api.drawStroke

/**
 * A rule across the width it is given, drawn by the stroke axis's divider in [color], which the
 * caller reads from the colour axis. As tall as the divider is wide, in whole pixels, and at least
 * one, so a hairline divider still takes a row of its own.
 */
@Composable
internal fun NxRule(color: Color, modifier: Modifier = Modifier) {
    val divider = Stroke.divider
    Canvas(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height = constraints.constrainHeight(divider.width().roundToPx().coerceAtLeast(1))
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        val middle = divider.style(StrokeState.Rest, this).width / 2
        drawStroke(divider, Offset(0f, middle), Offset(size.width, middle), SolidColor(color))
    }
}
