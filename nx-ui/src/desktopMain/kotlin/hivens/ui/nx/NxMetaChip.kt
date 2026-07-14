package hivens.ui.nx

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hivens.ui.theme.NxTheme

/**
 * Where a [NxMetaChip] sits, which fixes its container + label colours:
 * - [OnMedia] / [OnMediaAccent] read white over banner art (a translucent
 *   black fill, or the accent fill for the emphasized one).
 * - [Surface] is the muted on-glass chip that leans on theme tokens.
 * - [Error] flags a problem (a missing dependency, etc).
 */
enum class NxMetaChipTone { OnMedia, OnMediaAccent, Surface, Error }

/**
 * Small read-only metadata pill (version, tag, license, "fork"). A disabled
 * [AssistChip] so it keeps Material's chip metrics; [tone] carries the only
 * thing the per-screen copies actually varied.
 */
@Composable
fun NxMetaChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: NxMetaChipTone = NxMetaChipTone.Surface,
) {
    val colors = NxTheme.colors
    val (container, label) = when (tone) {
        NxMetaChipTone.OnMedia       -> Color.Black.copy(alpha = 0.35f) to Color.White
        NxMetaChipTone.OnMediaAccent -> colors.primary.copy(alpha = 0.85f) to Color.White
        NxMetaChipTone.Surface       -> colors.outline.copy(alpha = 0.2f) to colors.textSecondary
        NxMetaChipTone.Error         -> colors.error.copy(alpha = 0.15f) to colors.error
    }
    AssistChip(
        onClick  = {},
        enabled  = false,
        modifier = modifier,
        shape    = MaterialTheme.shapes.extraSmall,
        label    = { Text(text, style = MaterialTheme.typography.labelSmall, color = label) },
        colors   = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor     = label,
        ),
        border   = null,
    )
}
