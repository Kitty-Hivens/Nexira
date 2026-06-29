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
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * A generic in-plane settings row: optional [icon] + [title] (+ [subtitle]) on the
 * left, a [trailing] slot on the right, optionally clickable. It draws no surface
 * of its own and is meant to sit INSIDE an [NxSection] plane next to [NxToggle] rows
 * (for a standalone shortcut on its own plane use [NxNavRow] instead). When [onClick]
 * is set the whole row is the hit target, with a shape-correct hover.
 */
@Composable
fun NxRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: IconKey? = null,
    iconTint: Color = NxTheme.colors.textSecondary,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(LocalStyle.current.cardCorner)
    val interaction = remember { MutableInteractionSource() }
    val clickMod = if (onClick != null) {
        Modifier
            .clip(shape)
            .clickable(interactionSource = interaction, indication = ShapedStateLayer(shape, iconTint), onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier              = modifier.fillMaxWidth().then(clickMod).padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Symbol(icon, null, tint = iconTint, size = 22.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
