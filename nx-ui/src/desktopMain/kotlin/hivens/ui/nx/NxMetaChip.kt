package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.NxTheme

/**
 * Where a [NxMetaChip] sits, which fixes its container + label colours:
 * - [OnMedia] / [OnMediaAccent] read white over banner art (a translucent
 *   black fill, or the accent fill for the emphasized one).
 * - [Surface] is the muted on-glass chip that leans on theme tokens.
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
    val colors = NxTheme.colors
    // Fill, ink, hairline. The reference outlines a tinted tag in its own hue and
    // a plain one in the neutral rule, which is what keeps a muted fact legible on
    // a card that is itself a raised plane: without the line the chip and the card
    // are two fills a shade apart and the chip stops having an edge.
    val (container, label, border) = when (tone) {
        NxMetaChipTone.OnMedia       -> Triple(Color.Black.copy(alpha = 0.35f), Color.White, Color.White.copy(alpha = 0.25f))
        NxMetaChipTone.OnMediaAccent -> Triple(colors.primary.copy(alpha = 0.85f), Color.White, Color.Transparent)
        NxMetaChipTone.Surface       -> Triple(colors.outline.copy(alpha = 0.2f), colors.textSecondary, colors.outline.copy(alpha = 0.45f))
        NxMetaChipTone.Success       -> Triple(colors.success.copy(alpha = 0.15f), colors.success, colors.success.copy(alpha = 0.4f))
        NxMetaChipTone.Warning       -> Triple(colors.warnAccent.copy(alpha = 0.15f), colors.warnAccent, colors.warnAccent.copy(alpha = 0.4f))
        NxMetaChipTone.Error         -> Triple(colors.error.copy(alpha = 0.15f), colors.error, colors.error.copy(alpha = 0.4f))
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
