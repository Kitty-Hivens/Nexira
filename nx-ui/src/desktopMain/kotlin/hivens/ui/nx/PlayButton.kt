package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * The Play call-to-action, shared by the Library card and the pack-detail hero
 * so the launch affordance reads the same everywhere. A decisive flat accent
 * plate on the style's button corner: state renders through the FILL itself
 * (hover lifts its luminance, press sinks it and compresses the plate a
 * touch), the glyph nudges forward on hover, and a top-lit hairline gives the
 * plate a bevel instead of a flat sticker. Disabled is a ghost outline -- a
 * visible capability that is not available right now, not a washed-out fill.
 * Colour tracks the theme accent; geometry and motion track
 * [hivens.ui.theme.StyleSpec]. [iconOnly] drops the label for tight layouts;
 * [compact] is the smaller card sizing vs. the larger hero sizing.
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
    val style = LocalStyle.current
    val shape = RoundedCornerShape(style.buttonCorner)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val fillTarget = when {
        !enabled -> Color.Transparent
        pressed  -> lerp(accent, Color.Black, 0.16f)
        hovered  -> lerp(accent, Color.White, 0.10f)
        else     -> accent
    }
    val fill by animateColorAsState(fillTarget, tween(style.animationDurationMs(110)), label = "playFill")
    val plateScale by animateFloatAsState(
        targetValue   = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(style.animationDurationMs(90)),
        label         = "playScale",
    )
    val glyphNudge by animateDpAsState(
        targetValue   = if (hovered && enabled) 2.dp else 0.dp,
        animationSpec = tween(style.animationDurationMs(110)),
        label         = "playNudge",
    )

    // Bevel: a top-lit hairline fading out downward, derived from the plate's
    // own accent. Disabled swaps it for a full ghost outline -- the plate's
    // shape stays, only its fill is gone.
    val bevel = Brush.verticalGradient(
        listOf(
            lerp(accent, Color.White, 0.45f).copy(alpha = 0.55f),
            Color.Transparent,
        ),
    )
    val ghost = Color.White.copy(alpha = 0.38f)
    val content = if (enabled) Color.White else Color.White.copy(alpha = 0.55f)

    val iconSize = when {
        iconOnly && compact -> 22.dp
        iconOnly            -> 24.dp
        compact             -> 18.dp
        else                -> 22.dp
    }
    val pad = when {
        iconOnly && compact -> PaddingValues(10.dp)
        iconOnly            -> PaddingValues(12.dp)
        compact             -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        else                -> PaddingValues(horizontal = 24.dp, vertical = 13.dp)
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = plateScale
                scaleY = plateScale
            }
            .clip(shape)
            .background(fill)
            .let { if (enabled) it.border(1.dp, bevel, shape) else it.border(1.5.dp, ghost, shape) }
            .clickable(
                interactionSource = interaction,
                indication        = null,
                enabled           = enabled,
                onClick           = onClick,
            )
            .padding(pad),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        Symbol(
            NxIcon.PlayArrow,
            contentDescription = if (iconOnly) label else null,
            tint               = content,
            size               = iconSize,
            modifier           = Modifier.offset(x = glyphNudge),
        )
        if (!iconOnly) {
            Text(
                text       = label,
                color      = content,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}
