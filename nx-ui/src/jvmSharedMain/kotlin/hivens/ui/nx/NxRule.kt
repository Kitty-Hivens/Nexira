package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.drawStroke

/**
 * A rule across the width it is given, drawn by the stroke axis's divider in [color], which the
 * caller reads from the colour axis.
 *
 * The room it takes is its own, [RULE_HEIGHT], whatever the divider answers: a look decides how the
 * rule is drawn and never how much room it takes, so a heavier or a finer divider moves nothing
 * around it. The stroke runs along the middle of that room and may draw past it.
 */
@Composable
internal fun NxRule(color: Color, modifier: Modifier = Modifier) {
    val divider = Stroke.divider
    Canvas(modifier.fillMaxWidth().height(RULE_HEIGHT)) {
        val middle = RULE_HEIGHT.toPx() / 2
        drawStroke(divider, Offset(0f, middle), Offset(size.width, middle), SolidColor(color))
    }
}

/** The room a rule takes in a column, the height of Material's divider. */
internal val RULE_HEIGHT = 1.dp
