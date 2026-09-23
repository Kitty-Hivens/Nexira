package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import kotlinx.serialization.Serializable

/** What a launch control is doing, which decides how it is drawn. */
enum class PlayTone {
    /** The verb is on offer: play, stop, sign in. The plate in full ink, pressable. */
    Ready,

    /**
     * Something is in progress and the control waits on it: a launch preparing, or
     * work on the files. The plate stays, and carries the progress.
     */
    Waiting,

    /** Refused for a reason that is not a fault, such as another game holding the launcher. */
    Unavailable,

    /** Refused because something is wrong and waiting will not fix it. */
    Problem,
}

/**
 * What the control is drawn over.
 *
 * A picture under a scrim is dark whatever the theme, so the inks there are fixed
 * light-on-dark. A plain surface belongs to the theme, and the inks come from it: the
 * old control assumed a darkened picture everywhere, which left its refusals as a white
 * outline on a light-grey card, next to invisible.
 */
enum class PlayGround { Media, Surface }

/**
 * Where a state's words go when the control is not simply offering its verb.
 *
 * [Plate] keeps them inside the control and fills the plate with the work's progress,
 * the way a game launcher's Play turns into its own progress bar. [Caption] keeps the
 * plate at one width and puts the sentence beside it, so nothing next to the control
 * is pushed about as the state changes.
 */
@Serializable
enum class PlayLayout { Plate, Caption }

/**
 * The launch call-to-action on the pack-detail hero and the home launch widgets. A
 * low plate in static monochrome ink, deliberately NOT the palette accent: a hero
 * ground is arbitrary art behind a dark scrim, and a stable ink reads on all of it
 * where an accent fill fought both the art and the neighbouring chips. Over media the
 * ink follows the theme (`#121318` in a dark one, white in a light one), as it always
 * has; over a surface it is the theme's inverse, since a near-black plate on a
 * near-black card is held apart by its hairline alone.
 *
 * No launch knowledge of its own. The caller names the state through [tone] and hands
 * over the words and glyph for it; the plate decides everything visual, so a state
 * cannot be drawn in a way the library did not choose.
 *
 * [progress] is the share of the work done, null when its size is unknown; it is only
 * read while [Waiting][PlayTone.Waiting]. [iconOnly] drops the words for a tight
 * layout, [compact] is the smaller sizing.
 *
 * The press-compress rides a graphics layer only WHILE it animates: an always-on
 * layer resamples the button as an offscreen texture, which softens every edge and
 * the glyph on a fractional-DPI display. At rest the plate draws straight into the
 * window, so it stays crisp.
 */
@Composable
fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconKey = NxIcon.PlayArrow,
    tone: PlayTone = PlayTone.Ready,
    progress: Float? = null,
    ground: PlayGround = PlayGround.Media,
    layout: PlayLayout = PlayLayout.Plate,
    iconOnly: Boolean = false,
    compact: Boolean = false,
) {
    val inks = playInks(ground)
    if (layout == PlayLayout.Caption && tone != PlayTone.Ready && !iconOnly) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Text(
                text       = label,
                color      = if (tone == PlayTone.Problem) inks.warn else inks.caption,
                // Over a picture the caption has no plate of its own, and the scrim
                // alone loses to a bright patch of art. A soft shadow keeps it apart
                // from whatever is under it; a surface needs none.
                style      = if (ground == PlayGround.Media) {
                    MaterialTheme.typography.labelLarge.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 6f))
                } else {
                    MaterialTheme.typography.labelLarge
                },
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f, fill = false),
            )
            Plate(label, onClick, Modifier, icon, tone, progress, inks, words = false, figures = true, compact = compact)
        }
    } else {
        Plate(label, onClick, modifier, icon, tone, progress, inks, words = !iconOnly, figures = !iconOnly, compact = compact)
    }
}

/**
 * The plate itself. [words] puts the label in it; [figures] lets it name the share
 * done while waiting. The caption layout takes the words out and keeps the figures,
 * so its plate says the one thing that changes by the second.
 */
@Composable
private fun Plate(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: IconKey,
    tone: PlayTone,
    progress: Float?,
    inks: PlayInks,
    words: Boolean,
    figures: Boolean,
    compact: Boolean,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val interactive = tone == PlayTone.Ready
    val solid = tone == PlayTone.Ready || tone == PlayTone.Waiting

    val fillTarget = when {
        !solid                 -> Color.Transparent
        pressed && interactive -> lerp(inks.plate, inks.onPlate, 0.16f)
        hovered && interactive -> lerp(inks.plate, inks.onPlate, 0.10f)
        else                   -> inks.plate
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

    val content = when (tone) {
        PlayTone.Ready       -> inks.onPlate
        PlayTone.Waiting     -> inks.onPlate.copy(alpha = 0.78f)
        PlayTone.Unavailable -> inks.quiet
        PlayTone.Problem     -> inks.quiet
    }
    val edge = when (tone) {
        PlayTone.Ready, PlayTone.Waiting -> inks.onPlate.copy(alpha = 0.18f)
        PlayTone.Unavailable             -> inks.quietLine
        PlayTone.Problem                 -> inks.warn.copy(alpha = 0.7f)
    }

    val iconSize = when {
        !words -> if (compact) 18.dp else 20.dp
        compact -> 16.dp
        else    -> 18.dp
    }
    val pad = when {
        !words && compact -> PaddingValues(7.dp)
        !words            -> PaddingValues(9.dp)
        compact           -> PaddingValues(horizontal = Spacing.s14, vertical = Spacing.s6)
        else              -> PaddingValues(horizontal = Spacing.s20, vertical = 9.dp)
    }
    val showFigures = figures && tone == PlayTone.Waiting && progress != null

    Row(
        modifier = modifier
            .then(
                // Layer present only while the compress is mid-animation; at
                // rest (scale == 1f) there is no offscreen texture to resample.
                if (plateScale != 1f) Modifier.graphicsLayer { scaleX = plateScale; scaleY = plateScale }
                else Modifier,
            )
            // At the width of the plain Play plate, so a state with fewer words than
            // the verb does not shrink the control out from under the pointer.
            .then(if (words || figures) Modifier.widthIn(min = PLATE_MIN_WIDTH) else Modifier)
            .clip(shape)
            .background(fill)
            .then(if (tone == PlayTone.Waiting) Modifier.workProgress(progress, inks.onPlate.copy(alpha = 0.14f)) else Modifier)
            .border(if (solid) 1.dp else 1.5.dp, edge, shape)
            .clickable(
                interactionSource = interaction,
                indication        = null,
                enabled           = interactive,
                onClick           = onClick,
            )
            .padding(pad),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp, Alignment.CenterHorizontally),
    ) {
        // A plate that is only counting shows the count and not the glyph: the glyph
        // names the state, and in the caption layout the words beside it already do.
        if (words || !showFigures) {
            Symbol(
                icon,
                contentDescription = if (words) null else label,
                tint               = if (tone == PlayTone.Problem) inks.warn else content,
                size               = iconSize,
                // Transport glyphs are solid, the way every player and launcher draws
                // them; the rest keep the outline the icon set reads in.
                fill               = if (icon == NxIcon.PlayArrow || icon == NxIcon.Stop) 1f else 0f,
                modifier           = Modifier.offset(x = glyphNudge),
            )
        }
        if (words) {
            Text(
                text       = label,
                color      = content,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        if (showFigures) {
            Text(
                text       = "${(progress!!.coerceIn(0f, 1f) * 100).toInt()}%",
                // Beside the words it is the lesser half; alone in the plate it is the
                // whole message and takes the verb's weight.
                color      = if (words) content.copy(alpha = 0.62f) else inks.onPlate,
                style      = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (words) FontWeight.SemiBold else FontWeight.Bold,
                maxLines   = 1,
            )
        }
    }
}

/** The inks a plate draws with, resolved for its ground. */
private class PlayInks(
    val plate: Color,
    val onPlate: Color,
    val quiet: Color,
    val quietLine: Color,
    val warn: Color,
    val caption: Color,
)

@Composable
private fun playInks(ground: PlayGround): PlayInks {
    val colors = NxTheme.colors
    val dark = colors.background.luminance() < 0.5f
    return when (ground) {
        PlayGround.Media -> PlayInks(
            plate     = if (dark) DARK_INK else Color.White,
            onPlate   = if (dark) Color.White else Color.Black,
            quiet     = Color.White.copy(alpha = 0.72f),
            quietLine = Color.White.copy(alpha = 0.38f),
            // The dark palette's warning, whatever the theme: the ground under it is
            // a darkened picture either way, and the light palette's amber is chosen
            // for a light ground and sinks into this one.
            warn      = MEDIA_WARN,
            caption   = Color.White.copy(alpha = 0.82f),
        )
        PlayGround.Surface -> PlayInks(
            plate     = if (dark) Color.White else DARK_INK,
            onPlate   = if (dark) Color.Black else Color.White,
            quiet     = colors.textSecondary,
            quietLine = colors.textSecondary.copy(alpha = 0.35f),
            warn      = colors.warnAccent,
            caption   = colors.textSecondary,
        )
    }
}

private val DARK_INK = Color(0xFF121318)
private val MEDIA_WARN = Color(0xFFE0B341)

/** The width of the plain Play plate at the longest shipped verb, measured off a render. */
private val PLATE_MIN_WIDTH = 116.dp
