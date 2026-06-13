package hivens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.model.ServerProfile
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import kotlin.math.abs

// ─── Per-server gradient (shared with SquareServerCard) ─────────────────────
// Each server gets a stable unique gradient derived from its name.

internal val SERVER_PALETTES = listOf(
    Pair(Color(0xFF7C3AED), Color(0xFF4F46E5)), // violet -> indigo
    Pair(Color(0xFF0EA5E9), Color(0xFF6366F1)), // sky -> violet
    Pair(Color(0xFF10B981), Color(0xFF0EA5E9)), // emerald -> sky
    Pair(Color(0xFFF59E0B), Color(0xFFEF4444)), // amber -> red
    Pair(Color(0xFFEC4899), Color(0xFF8B5CF6)), // pink -> purple
    Pair(Color(0xFF14B8A6), Color(0xFF3B82F6)), // teal -> blue
    Pair(Color(0xFFF97316), Color(0xFFEAB308)), // orange -> yellow
    Pair(Color(0xFF6366F1), Color(0xFFEC4899)), // indigo -> pink
)

internal fun serverPalette(name: String): Pair<Color, Color> =
    SERVER_PALETTES[abs(name.hashCode()) % SERVER_PALETTES.size]

/**
 * Compact full-width capsule row for a server -- the narrow-width alternative to
 * [SquareServerCard]. One per row, stacked vertically. Click selects, Enter
 * launches the selected one; fav/settings/details surface on hover.
 */
@Composable
fun ServerPill(
    profile: ServerProfile,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onLaunch: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onToggleFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isFocused by interaction.collectIsFocusedAsState()
    val showActions = isHovered || isFocused
    val (colorA, colorB) = remember(profile.name) { serverPalette(profile.name) }

    val borderColor = when {
        isSelected -> CelestiaTheme.colors.primary
        isFocused  -> CelestiaTheme.colors.textPrimary
        else       -> CelestiaTheme.colors.outline.copy(alpha = 0.25f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.45f))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .hoverable(interaction)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                    if (isSelected) onLaunch() else onSelect()
                    true
                } else false
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(listOf(colorA.copy(alpha = 0.85f), colorB.copy(alpha = 0.70f))),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = profile.name.take(2).uppercase(),
                fontSize   = 13.sp,
                fontWeight = FontWeight.Black,
                color      = Color.White,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text       = profile.title ?: profile.name,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = profile.version,
                style    = MaterialTheme.typography.labelSmall,
                color    = CelestiaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(visible = showActions, enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                PillIconButton(
                    icon  = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    tint  = if (isFavorite) Color(0xFFEF4444) else CelestiaTheme.colors.textSecondary,
                    onClick = onToggleFav,
                )
                PillIconButton(Icons.Default.Settings, tint = CelestiaTheme.colors.textSecondary, onClick = onSettings)
                PillIconButton(Icons.Default.Info, tint = CelestiaTheme.colors.textSecondary, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun PillIconButton(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}
