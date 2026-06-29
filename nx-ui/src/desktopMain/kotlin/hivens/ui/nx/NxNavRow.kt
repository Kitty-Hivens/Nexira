package hivens.ui.nx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * A clickable navigation row: icon + title (+ optional [subtitle]) + a trailing
 * chevron, on a library-owned opaque body plane with a shape-correct hover. The
 * one "tap to go somewhere" row for settings shortcuts, replacing per-screen
 * `glassSurfaceAlpha` + raw `clickable` rows (Rule 0/5).
 */
@Composable
fun NxNavRow(
    icon: IconKey,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: IconKey = NxIcon.ChevronRight,
    iconTint: Color = NxTheme.colors.primary,
) {
    val shape = RoundedCornerShape(LocalStyle.current.cardCorner)
    val interaction = remember { MutableInteractionSource() }
    NxSurface(
        level    = NxSurfaceLevel.Raised,
        glass    = false,
        shape    = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(interactionSource = interaction, indication = ShapedStateLayer(shape, iconTint), onClick = onClick),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, null, tint = iconTint, size = 24.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
                    }
                }
            }
            Symbol(trailing, null, tint = NxTheme.colors.textSecondary)
        }
    }
}
