package hivens.ui.nx

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * A clickable navigation row: icon + title (+ optional [subtitle]) + a trailing
 * chevron, on a library-owned opaque body plane. The one "tap to go somewhere" row
 * for settings shortcuts, replacing the per-screen hand-mixed fill + raw `clickable`
 * rows (Rule 0/5). Its hover/press uses the same soft NEUTRAL overlay as the in-plane
 * [NxRow] ([softHoverAlpha]) so a navigable row reads the same on its own plane or in
 * one. The [icon] keeps its [iconTint] accent.
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
    val alpha = softHoverAlpha(interaction)
    NxSurface(
        // Same level as an NxSection plane so a standalone nav card reads as the same
        // material as the section planes around it, not a step-darker odd one out.
        level    = NxSurfaceLevel.Floating,
        blurDp   = 0f,
        shape    = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .background(NxTheme.colors.textPrimary.copy(alpha = alpha))
                .padding(Spacing.s16),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, null, tint = iconTint, size = 24.dp)
                Spacer(Modifier.width(Spacing.s16))
                Column {
                    Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Symbol(trailing, null, tint = NxTheme.colors.textSecondary)
        }
    }
}
