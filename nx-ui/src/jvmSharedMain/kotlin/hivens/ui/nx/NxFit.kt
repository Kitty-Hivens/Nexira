package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

/**
 * Shows [content] when it fits and [compact] when it does not.
 *
 * Measured rather than guessed. The obvious alternative is a width threshold
 * below which the small version is used, and it is wrong in a way that only
 * shows up later: the same row of controls is a different width in every
 * language, so a threshold tuned on one locale clips another. Here the full
 * version is measured against the space actually available and the answer is
 * whatever it turns out to be.
 *
 * The cost is a second measure pass in the frames where the fallback is needed,
 * which is why this is for a bar of controls rather than for a list row.
 */
@Composable
fun NxFit(
    modifier: Modifier = Modifier,
    compact: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        // Measured unbounded: what is wanted, not what would be survived. Measuring
        // against the real constraint would let the content squeeze itself and
        // report a fit that only looks like one.
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val preferred = subcompose(FitSlot.Preferred, content).map { it.measure(loose) }
        val wanted = preferred.maxOfOrNull { it.width } ?: 0

        val placeables = if (wanted <= constraints.maxWidth) {
            preferred
        } else {
            subcompose(FitSlot.Compact, compact).map { it.measure(constraints) }
        }

        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        // Answered within the incoming constraints, not merely under the ceiling.
        // Reporting a content size under a fillMaxWidth or a fixed height breaks
        // the contract the parent measured against.
        layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private enum class FitSlot { Preferred, Compact }

@Preview
@Composable
private fun NxFitDarkPreview() = FitWidths(dark = true)

@Preview
@Composable
private fun NxFitLightPreview() = FitWidths(dark = false)

@Composable
private fun FitWidths(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(480.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (room in listOf(440.dp, 200.dp)) {
                NxFit(
                    modifier = Modifier.width(room),
                    compact  = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            NxIconButton(NxIcon.FolderOpen, "Open folder", onClick = {}, tint = NxInk.main)
                            NxIconButton(NxIcon.Refresh, "Refresh", onClick = {}, tint = NxInk.main)
                        }
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxButton("Open folder", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.FolderOpen, compact = true)
                        NxButton("Refresh", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.Refresh, compact = true)
                    }
                }
            }
        }
    }
}
