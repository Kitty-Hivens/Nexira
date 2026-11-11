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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.surface.SurfaceKind

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
    /** Null is the theme's lead colour, resolved on the row's own plane. */
    iconTint: Color? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val alpha = softHoverAlpha(interaction)
    val tint = NxInk.main
    NxSurface(
        // A panel, like the section planes around it, so a standalone nav card reads
        // as the same material rather than as an odd one out.
        kind     = SurfaceKind.Panel,
        shape    = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .drawBehind { drawSoftHover(tint, alpha.value) }
                .padding(Spacing.s16),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, null, tint = iconTint ?: NxColor.lead(), size = 24.dp)
                Spacer(Modifier.width(Spacing.s16))
                Column {
                    Text(title, color = NxInk.main, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Symbol(trailing, null, tint = NxInk.quiet)
        }
    }
}
