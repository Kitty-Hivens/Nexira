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
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Layout primitive. Renders 32dp of empty vertical space; in edit
// mode a faint dashed center line shows the widget is there and
// grabbable. Props in Phase 5 will let the user pick the height; for
// now it is fixed so the editor can demonstrate reorder without prop
// UI.
@Widget(id = "home.new.spacer", displayName = "Spacer")
@Composable
fun SpacerWidget(instance: WidgetInstance) {
    val edit = LocalEditMode.current is EditModeState.On
    if (!edit) {
        Spacer(Modifier.fillMaxWidth().height(32.dp))
        return
    }
    val lineColor = CelestiaTheme.colors.outline.copy(alpha = 0.35f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
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
