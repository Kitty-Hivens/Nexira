package hivens.ui.widgets

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

// Content scale for a size-responsive widget. 1f in flow slots (Column/Row --
// one axis unbounded) so existing placements render byte-identical to today;
// it deviates from 1f only inside a both-bounded Canvas footprint, where the
// content scales to the footprint relative to the widget's natural reference
// size. Sub-composables read this local; AdaptiveWidget also hands it to the
// content lambda directly.
val LocalWidgetScale = compositionLocalOf { 1f }

private const val MIN_WIDGET_SCALE = 0.4f
private const val MAX_WIDGET_SCALE = 4f

// Wraps a widget's content with a footprint-relative content scale.
//
// The widget does NOT grow to fill the available space -- the bounded size IS
// its footprint (its default grid span, or a resized span on the canvas), and
// the content adapts to that footprint. In a flow slot only one axis is bounded
// (Column: width bounded, height wrap), so the scale is 1f and the widget
// renders exactly as before. In a Canvas cell both axes are bounded, so the
// content scales to the footprint vs the reference size.
//
// `referenceWidth/Height` are the widget's natural ("1x") content size -- the
// footprint at which it looks correct without scaling.
@Composable
fun AdaptiveWidget(
    referenceWidth: Dp,
    referenceHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scale = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
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

// Scales a text style's font size by the widget scale. Identity at 1f so flow
// placements are untouched.
fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale)
