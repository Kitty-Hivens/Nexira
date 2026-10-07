package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/**
 * Where a [NxMetaChip] sits, which fixes its container + label colours:
 * - [OnMedia] / [OnMediaAccent] sit over banner art (a translucent black fill, or
 *   the theme's lead colour for the emphasized one).
 * - [Surface] is the muted chip on a plane.
 * - [Success] marks a good/safe state (installed build, green compat).
 * - [Warning] marks a needs-care state (structural change, prerelease channel).
 * - [Error] flags a problem (a missing dependency, etc).
 */
enum class NxMetaChipTone { OnMedia, OnMediaAccent, Surface, Success, Warning, Error }

/**
 * Small read-only metadata pill (version, tag, license, "fork"), drawn on the
 * shared [NxPill] shell; [tone] carries the only thing the per-screen copies
 * actually varied.
 *
 * [dot] adds the leading state marker, and [onClick] makes the badge a target --
 * together they cover the live-state badges that used to be hand-rolled pills on
 * the Library card.
 */
@Composable
fun NxMetaChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: NxMetaChipTone = NxMetaChipTone.Surface,
    dot: Color? = null,
    /** A drawn mark in the dot's place: a loader's own logo rather than a swatch. */
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // Fill first, then everything on it is read against the fill. The reference
    // outlines a tinted tag in its own hue and a plain one in the neutral rule, which
    // is what keeps a muted fact legible on a card: without the line the chip and the
    // card are two fills a shade apart and the chip stops having an edge.
    //
    // Over media the inks are fixed light-on-dark on purpose: the picture is under a
    // scrim whatever the theme, so a theme ink there would read on nothing.
    val statusOf: Status? = when (tone) {
        NxMetaChipTone.Success -> Status.Success
        NxMetaChipTone.Warning -> Status.Warning
        NxMetaChipTone.Error -> Status.Error
        else -> null
    }
    val container = when {
        tone == NxMetaChipTone.OnMedia -> Color.Black.copy(alpha = 0.35f)
        tone == NxMetaChipTone.OnMediaAccent -> NxColor.lead().copy(alpha = 0.85f)
        statusOf != null -> NxColor.wash(NxColor.status(statusOf), 0.15f)
        else -> NxColor.wash(NxInk.quiet, 0.12f)
    }
    OnFill(container) {
        val (label, border) = when {
            tone == NxMetaChipTone.OnMedia -> Color.White to Color.White.copy(alpha = 0.25f)
            tone == NxMetaChipTone.OnMediaAccent -> NxColor.on(NxColor.lead()) to Color.Transparent
            statusOf != null -> NxColor.status(statusOf, text = true) to NxColor.status(statusOf).copy(alpha = 0.4f)
            else -> NxInk.quiet to NxInk.line
        }
        NxPill(
            text       = text,
            container  = container,
            label      = label,
            border     = border,
            modifier   = modifier,
            // Over art the label competes with the picture; on a flat surface it must
            // not outweigh the value it annotates.
            fontWeight = when (tone) {
                NxMetaChipTone.OnMedia, NxMetaChipTone.OnMediaAccent -> FontWeight.Medium
                else -> null
            },
            dot        = dot,
            leading    = leading,
            onClick    = onClick,
        )
    }
}
