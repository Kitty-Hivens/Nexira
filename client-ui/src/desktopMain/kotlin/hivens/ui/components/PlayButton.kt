package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * The Play call-to-action, shared by the Library card and the pack-detail hero so
 * the launch affordance reads the same everywhere. A vertical accent gradient
 * (lighter top -> darker bottom) gives it a bit of depth instead of the flat
 * single-colour fill, on a softly rounded rectangle. Colour still tracks the theme
 * accent, so a theme switch recolours it. [iconOnly] drops the label for tight
 * layouts; [compact] is the smaller card sizing vs. the larger hero sizing.
 */
@Composable
fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconOnly: Boolean = false,
    compact: Boolean = false,
) {
    val accent = NxTheme.colors.primary
    val fill: Brush = if (enabled) {
        Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.18f), lerp(accent, Color.Black, 0.12f)))
    } else {
        SolidColor(accent.copy(alpha = 0.45f))
    }
    val content = if (enabled) Color.White else Color.White.copy(alpha = 0.6f)
    val iconSize = when {
        iconOnly && compact -> 22.dp
        iconOnly            -> 24.dp
        compact             -> 18.dp
        else                -> 20.dp
    }
    val pad = when {
        iconOnly && compact -> PaddingValues(10.dp)
        iconOnly            -> PaddingValues(12.dp)
        compact             -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        else                -> PaddingValues(horizontal = 22.dp, vertical = 12.dp)
    }

    Row(
        modifier              = modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(pad),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        Symbol(
            NxIcon.PlayArrow,
            contentDescription = if (iconOnly) label else null,
            tint               = content,
            size               = iconSize,
        )
        if (!iconOnly) {
            Text(
                text       = label,
                color      = content,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
