package hivens.ui.widgets

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.api.LocalWidgetFootprintDp

// Content scale for a size-responsive widget. 1f wherever nothing has chosen a
// footprint, so the widget draws at its reference size; it deviates from 1f only
// inside a footprint somebody set, where the content scales to it. Sub-composables
// read this local; AdaptiveWidget also hands it to the content lambda directly.
val LocalWidgetScale = compositionLocalOf { 1f }

private const val MIN_WIDGET_SCALE = 0.4f
private const val MAX_WIDGET_SCALE = 4f

/**
 * Draws a widget at [referenceWidth] by [referenceHeight], or scaled into the
 * footprint when it has been given one.
 *
 * The widget does NOT grow to fill whatever space is available. Its reference
 * size is the size it looks correct at, and that is what it draws at until
 * somebody places it at a size of their own. Then the footprint IS the size and
 * the content scales to it.
 *
 * ## Bounded is not the same as chosen
 *
 * This used to scale whenever both axes arrived bounded, which reads as "I am in
 * a cell" and is not what the constraint means. A Column inside a slot that fills
 * its surface hands each child the whole remaining height, so the clock in an
 * ordinary vertical slot measured its 200 by 230 against a thousand points of
 * leftover, hit the 4x ceiling and drew a 140dp dial across 2974 points, pushing
 * every widget under it off the pane. Measured in the running launcher, not
 * reasoned about.
 *
 * So the question it asks is [LocalWidgetFootprintDp], which the renderer fills
 * in from the widget's own stored size and leaves at zero when nothing named one.
 */
@Composable
fun AdaptiveWidget(
    referenceWidth: Dp,
    referenceHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    val footprint = LocalWidgetFootprintDp.current
    val placed = footprint.width > 0f && footprint.height > 0f
    // Without a footprint the reference size is the whole answer, and pinning it
    // here is what keeps the content's own fillMaxSize from taking the slot.
    val sized = if (placed) modifier else modifier.size(referenceWidth, referenceHeight)
    BoxWithConstraints(sized) {
        val scale = if (placed && constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            minOf(maxWidth / referenceWidth, maxHeight / referenceHeight)
                .coerceIn(MIN_WIDGET_SCALE, MAX_WIDGET_SCALE)
        } else {
            1f
        }
        CompositionLocalProvider(LocalWidgetScale provides scale) {
            content(scale)
        }
    }
}

// Scales a text style's font size by the widget scale. Identity at 1f so an
// unplaced widget is untouched.
fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale)
