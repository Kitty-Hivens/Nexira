package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

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
