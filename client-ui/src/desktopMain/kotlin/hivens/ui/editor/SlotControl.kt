package hivens.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath

// Phase G edit-mode slot control. A compact trigger (slot id + caret) opens a
// menu to set the slot's orientation (+ grid columns). Compact + popup-based so
// it fits a narrow slot -- the inline four-chip row overflowed and wrapped on
// the 64dp left rail and the 200dp profile nav. The slot id is on the trigger so
// multiple slot controls on one surface (the rail's top/bottom) stay distinct.
@Composable
internal fun SlotControl(path: SlotPath, content: SlotContent, controller: EditModeController) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(glassSurfaceAlpha(0.8f))
                .clickable { open = true }
                .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        ) {
            Text(
                text       = path.leafAddress.slot.value,
                style      = MaterialTheme.typography.labelSmall,
                color      = CelestiaTheme.colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector        = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint               = CelestiaTheme.colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OrientItem(s.editorSlotStack, content.orientation == SlotOrientation.Column) {
                controller.setSlotOrientation(path, SlotOrientation.Column); open = false
            }
            OrientItem(s.editorSlotRow, content.orientation == SlotOrientation.Row) {
                controller.setSlotOrientation(path, SlotOrientation.Row); open = false
            }
            OrientItem(s.editorSlotGrid, content.orientation == SlotOrientation.Grid) {
                controller.setSlotOrientation(path, SlotOrientation.Grid); open = false
            }
            OrientItem(s.editorSlotCanvas, content.orientation == SlotOrientation.Canvas) {
                controller.setSlotOrientation(path, SlotOrientation.Canvas); open = false
            }
            if (content.orientation == SlotOrientation.Grid) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier              = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    StepChip("-") { controller.setGridColumns(path, content.gridColumns - 1) }
                    Text(
                        text  = "${content.gridColumns}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CelestiaTheme.colors.textPrimary,
                    )
                    StepChip("+") { controller.setGridColumns(path, content.gridColumns + 1) }
                }
            }
        }
    }
}

@Composable
private fun OrientItem(label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text       = label,
                color      = if (active) CelestiaTheme.colors.primary else CelestiaTheme.colors.textPrimary,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    Text(
        text     = label,
        style    = MaterialTheme.typography.labelMedium,
        color    = CelestiaTheme.colors.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(glassSurfaceAlpha(0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
