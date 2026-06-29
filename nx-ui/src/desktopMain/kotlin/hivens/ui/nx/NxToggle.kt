package hivens.ui.nx

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * One label(+ optional [description] / [icon]) and an [NxSwitch], as a single
 * in-plane settings row. The one toggle row every section composes, so a
 * boolean setting reads the same everywhere instead of each screen rolling its
 * own switch row (Rule 0/D04). [enabled] greys the row; [accent] overrides the
 * checked track for semantic toggles.
 */
@Composable
fun NxToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: IconKey? = null,
    enabled: Boolean = true,
    accent: Color? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier              = modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Symbol(icon, null, tint = NxTheme.colors.textSecondary.copy(alpha = alpha), size = 22.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text       = label,
                    color      = NxTheme.colors.textPrimary.copy(alpha = alpha),
                    fontWeight = FontWeight.Medium,
                )
                if (description != null) {
                    Text(
                        text  = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary.copy(alpha = alpha),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        NxSwitch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            enabled         = enabled,
            accent          = accent,
        )
    }
}
