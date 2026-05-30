package hivens.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath

// Phase G edit-mode slot control. Rendered at the start of a slot (via
// LocalSlotControlDecorator) for the selected surface. Sets the slot's
// orientation and, for Grid, the column count. Compact so it does not
// dominate the slot it sits in.
@Composable
internal fun SlotControl(path: SlotPath, content: SlotContent, controller: EditModeController) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(glassSurfaceAlpha(0.8f))
            .padding(3.dp),
    ) {
        OrientChip("Стек", content.orientation == SlotOrientation.Column) {
            controller.setSlotOrientation(path, SlotOrientation.Column)
        }
        OrientChip("Ряд", content.orientation == SlotOrientation.Row) {
            controller.setSlotOrientation(path, SlotOrientation.Row)
        }
        OrientChip("Сетка", content.orientation == SlotOrientation.Grid) {
            controller.setSlotOrientation(path, SlotOrientation.Grid)
        }
        OrientChip("Холст", content.orientation == SlotOrientation.Canvas) {
            controller.setSlotOrientation(path, SlotOrientation.Canvas)
        }
        if (content.orientation == SlotOrientation.Grid) {
            StepChip("-") { controller.setGridColumns(path, content.gridColumns - 1) }
            Text(
                text     = "${content.gridColumns}",
                style    = MaterialTheme.typography.labelSmall,
                color    = CelestiaTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            StepChip("+") { controller.setGridColumns(path, content.gridColumns + 1) }
        }
    }
}

@Composable
private fun OrientChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text       = label,
        style      = MaterialTheme.typography.labelSmall,
        color      = if (active) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier   = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) CelestiaTheme.colors.primary.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    Text(
        text     = label,
        style    = MaterialTheme.typography.labelSmall,
        color    = CelestiaTheme.colors.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(glassSurfaceAlpha(0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
