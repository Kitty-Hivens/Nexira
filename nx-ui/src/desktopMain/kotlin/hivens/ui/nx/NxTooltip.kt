package hivens.ui.nx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * Hover tooltip for desktop pointers: [text] appears near the cursor after a
 * short delay. [enabled] false renders [content] bare with no hover machinery
 * -- callers gate it on actual need (e.g. a caption that is not truncated has
 * nothing to reveal).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NxTooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        // Keep the caller's modifier (weights, sizes) identical across the
        // enabled flip so toggling the tooltip never re-lays-out the content.
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        tooltip = {
            Surface(
                color = NxTheme.colors.surface,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = NxTheme.colors.textSecondary.copy(alpha = 0.18f),
                    shape = MaterialTheme.shapes.extraSmall,
                ),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.textPrimary,
                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = Spacing.s8, vertical = Spacing.s4),
                )
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 12.dp)),
        modifier = modifier,
    ) {
        content()
    }
}
