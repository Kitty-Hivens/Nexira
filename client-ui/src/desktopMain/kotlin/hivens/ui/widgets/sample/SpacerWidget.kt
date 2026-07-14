package hivens.ui.widgets.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class SpacerProps(
    @PropLabel("widget.home.new.spacer.height") @PropRange(8.0, 160.0) val height: Int = 32,
)

// Layout primitive. Renders empty vertical space (height is a prop); in
// edit mode a faint dashed center line shows the widget is there and
// grabbable.
@Widget(id = "home.new.spacer", displayName = "widget.home.new.spacer", propsClass = SpacerProps::class)
@Composable
fun SpacerWidget(instance: WidgetInstance) {
    val h = instance.rememberProps<SpacerProps>().height.dp
    val edit = LocalEditMode.current is EditModeState.On
    if (!edit) {
        Spacer(Modifier.fillMaxWidth().height(h))
        return
    }
    val lineColor = NxTheme.colors.outline.copy(alpha = 0.35f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(h),
    ) {
        val y = size.height / 2f
        drawLine(
            color       = lineColor,
            start       = Offset(0f, y),
            end         = Offset(size.width, y),
            strokeWidth = 1.5f,
            cap         = StrokeCap.Round,
            pathEffect  = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
        )
    }
}
