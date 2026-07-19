package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * The launch call-to-action on the pack-detail hero. A low pill in static
 * monochrome ink -- black plate in a dark theme, white in a light one --
 * deliberately NOT the palette accent: the hero ground is arbitrary art behind
 * a dark scrim, and a stable ink reads on all of it while an accent fill
 * fought both the art and the neighbouring chips. The pill follows the style
 * axis the way NxSwitch does: a full capsule under a rounded style, the
 * style's own hard edge under a square one.
 *
 * One button, three moments, zero launch-state knowledge of its own: the
 * caller swaps [label] / [icon] / [onClick] per state, and [busy] renders the
 * non-interactive wait moment (dimmed plate, gently pulsing content -- static
 * dim when the style disables motion). Disabled is a ghost outline -- a
 * visible capability that is not available right now; its ink stays white
 * because the ghost reads against the hero's scrim, not against the theme.
 * [iconOnly] drops the label for tight layouts; [compact] is the smaller
 * sizing.
 */
@Composable
fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: IconKey = NxIcon.PlayArrow,
    iconOnly: Boolean = false,
    compact: Boolean = false,
) {
    val style = LocalStyle.current
    val shape =
        if (style.buttonCorner > 0.dp) RoundedCornerShape(percent = 50)
        else RoundedCornerShape(style.buttonCorner)
    val darkTheme = NxTheme.colors.background.luminance() < 0.5f
    val ink = if (darkTheme) Color.Black else Color.White
    val inkContent = if (darkTheme) Color.White else Color.Black

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val interactive = enabled && !busy

    val fillTarget = when {
        !enabled               -> Color.Transparent
        busy                   -> ink.copy(alpha = 0.75f)
        pressed && interactive -> lerp(ink, inkContent, 0.05f)
        hovered && interactive -> lerp(ink, inkContent, 0.10f)
        else                   -> ink
    }
    val fill by animateColorAsState(fillTarget, tween(style.animationDurationMs(110)), label = "playFill")
    val plateScale by animateFloatAsState(
        targetValue   = if (pressed && interactive) 0.97f else 1f,
        animationSpec = tween(style.animationDurationMs(90)),
        label         = "playScale",
    )
    val glyphNudge by animateDpAsState(
        targetValue   = if (hovered && interactive) 2.dp else 0.dp,
        animationSpec = tween(style.animationDurationMs(110)),
        label         = "playNudge",
    )

    val contentPulse: Float = if (busy && style.animationMultiplier > 0f) {
        val transition = rememberInfiniteTransition(label = "playBusy")
        val a by transition.animateFloat(
            initialValue  = 0.55f,
            targetValue   = 0.95f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label         = "playBusyAlpha",
        )
        a
    } else if (busy) 0.75f else 1f

    val ghost = Color.White.copy(alpha = 0.38f)
    // A faint ring in the opposite ink holds the pill's edge when the ground
    // behind it drifts toward the fill's own tone (dark art under a black
    // pill, pale art under a white one).
    val ring = inkContent.copy(alpha = 0.18f)
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.55f)
        else     -> inkContent.copy(alpha = contentPulse)
    }

    val iconSize = when {
        iconOnly -> if (compact) 18.dp else 20.dp
        compact  -> 16.dp
        else     -> 18.dp
    }
    val pad = when {
        iconOnly && compact -> PaddingValues(7.dp)
        iconOnly            -> PaddingValues(9.dp)
        compact             -> PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        else                -> PaddingValues(horizontal = 20.dp, vertical = 9.dp)
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = plateScale
                scaleY = plateScale
            }
            .clip(shape)
            .background(fill)
            .let { if (enabled) it.border(1.dp, ring, shape) else it.border(1.5.dp, ghost, shape) }
            .clickable(
                interactionSource = interaction,
                indication        = null,
                enabled           = interactive,
                onClick           = onClick,
            )
            .padding(pad),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp),
    ) {
        Symbol(
            icon,
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
