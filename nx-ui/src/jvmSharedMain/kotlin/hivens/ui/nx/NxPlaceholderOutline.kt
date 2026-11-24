package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.drawStroke
import hivens.ui.theme.NxColor
import hivens.ui.theme.Previewed

/**
 * The outline of a place that is empty or can take something, an empty slot or a drop target, drawn
 * by the stroke axis's placeholder in [color] along [shape] on the bounds [modifier] gives it, the
 * middle of the stroke on the edge.
 */
@Composable
fun NxPlaceholderOutline(color: Color, shape: Shape, modifier: Modifier = Modifier) {
    val placeholder = Stroke.placeholder
    val brush = SolidColor(color)
    Canvas(modifier) {
        drawStroke(placeholder, shape.createOutline(size, layoutDirection, this), brush)
    }
}

@Preview
@Composable
private fun NxPlaceholderOutlineDarkPreview() = PlaceholderOutlines(dark = true)

@Preview
@Composable
private fun NxPlaceholderOutlineLightPreview() = PlaceholderOutlines(dark = false)

@Composable
private fun PlaceholderOutlines(dark: Boolean) {
    Previewed(dark = dark) {
        Row(
            modifier              = Modifier.background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(160.dp, 80.dp)) {
                NxPlaceholderOutline(NxColor.lead(), RoundedCornerShape(12.dp), Modifier.fillMaxSize())
            }
            Box(Modifier.size(80.dp)) {
                NxPlaceholderOutline(NxColor.lead().copy(alpha = 0.45f), RoundedCornerShape(50), Modifier.fillMaxSize())
            }
        }
    }
}
