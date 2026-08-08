package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme

/** Corner of the Play plate under a rounded style -- a rounded rectangle
 *  between a bare rectangle and a full stadium, not a pill. */
private val PLATE_CORNER = 12.dp

/**
 * The launch call-to-action on the pack-detail hero and the home launch
 * widgets. A low plate in static monochrome ink -- `#121318` in a dark theme,
 * white in a light one -- deliberately NOT the palette accent: the hero ground
 * is arbitrary art behind a dark scrim, and a stable ink reads on all of it
 * where an accent fill fought both the art and the neighbouring chips. The
 * corner follows the style axis: a rounded rectangle under a rounded style,
 * the style's own hard edge under a square one.
 *
 * One button, three moments, zero launch-state knowledge of its own: the
 * caller swaps [label] / [icon] / [onClick] per state, and [busy] renders the
 * non-interactive wait moment (dimmed plate, gently pulsing content -- static
 * dim when the style disables motion). Disabled is a ghost outline -- a
 * visible capability that is not available right now; its ink stays white
 * because the ghost reads against the hero's scrim, not against the theme.
 * [iconOnly] drops the label for tight layouts; [compact] is the smaller
 * sizing.
 *
 * The press-compress rides a graphics layer only WHILE it animates: an
 * always-on layer resamples the button as an offscreen texture, which softens
 * every edge and the glyph on a fractional-DPI display. At rest the plate
 * draws straight into the window, so it stays crisp.
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
    val shape = RoundedCornerShape(if (style.buttonCorner > 0.dp) PLATE_CORNER else 0.dp)
    val darkTheme = NxTheme.colors.background.luminance() < 0.5f
    val ink = if (darkTheme) Color(0xFF121318) else Color.White
    val inkContent = if (darkTheme) Color.White else Color.Black

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val interactive = enabled && !busy

    val fillTarget = when {
        !enabled               -> Color.Transparent
        busy                   -> ink.copy(alpha = 0.78f)
        pressed && interactive -> lerp(ink, inkContent, 0.16f)
        hovered && interactive -> lerp(ink, inkContent, 0.10f)
        else                   -> ink
    }
    val fill by animateColorAsState(fillTarget, Motion.tap.of(), label = "playFill")
    val plateScale by animateFloatAsState(
        targetValue   = if (pressed && interactive) 0.97f else 1f,
        animationSpec = Motion.tap,
        label         = "playScale",
    )
    val glyphNudge by animateDpAsState(
        targetValue   = if (hovered && interactive) 2.dp else 0.dp,
        animationSpec = Motion.tap.of(),
        label         = "playNudge",
    )

    val pulseSpec = Motion.drift
    val contentPulse: Float = if (busy && !Motion.isStill) {
        val transition = rememberInfiniteTransition(label = "playBusy")
        val a by transition.animateFloat(
            initialValue  = 0.55f,
            targetValue   = 0.95f,
            animationSpec = infiniteRepeatable(pulseSpec.of(), RepeatMode.Reverse),
            label         = "playBusyAlpha",
        )
        a
    } else if (busy) 0.75f else 1f

    val ghost = Color.White.copy(alpha = 0.38f)
    // A faint ring in the opposite ink holds the plate's edge when the ground
    // behind it drifts toward the fill's own tone (dark art under the dark
    // plate, pale art under a white one).
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
            .then(
                // Layer present only while the compress is mid-animation; at
                // rest (scale == 1f) there is no offscreen texture to resample.
                if (plateScale != 1f) Modifier.graphicsLayer { scaleX = plateScale; scaleY = plateScale }
                else Modifier,
            )
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
