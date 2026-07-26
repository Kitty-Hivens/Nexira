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
    onClick: (() -> Unit)? = null,
) {
    val colors = NxTheme.colors
    val (container, label) = when (tone) {
        NxMetaChipTone.OnMedia       -> Color.Black.copy(alpha = 0.35f) to Color.White
        NxMetaChipTone.OnMediaAccent -> colors.primary.copy(alpha = 0.85f) to Color.White
        NxMetaChipTone.Surface       -> colors.outline.copy(alpha = 0.2f) to colors.textSecondary
        NxMetaChipTone.Success       -> colors.success.copy(alpha = 0.15f) to colors.success
        NxMetaChipTone.Warning       -> colors.warnAccent.copy(alpha = 0.15f) to colors.warnAccent
        NxMetaChipTone.Error         -> colors.error.copy(alpha = 0.15f) to colors.error
    }
    NxPill(
        text       = text,
        container  = container,
        label      = label,
        modifier   = modifier,
        // Over art the label competes with the picture; on a flat surface it must
        // not outweigh the value it annotates.
        fontWeight = when (tone) {
            NxMetaChipTone.OnMedia, NxMetaChipTone.OnMediaAccent -> FontWeight.Medium
            else -> null
        },
        dot        = dot,
        onClick    = onClick,
    )
}
