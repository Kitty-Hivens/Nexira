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
import hivens.widget.api.LocalWidgetSizing
import hivens.widget.model.WidgetSizing

// Content scale for a size-responsive widget. 1f wherever nothing has chosen a
// footprint, so the widget draws at its reference size; it deviates from 1f only
// inside a footprint somebody set, where the content scales to it. Sub-composables
// read this local; AdaptiveWidget also hands it to the content lambda directly.
val LocalWidgetScale = compositionLocalOf { 1f }

/**
 * How far a widget that declares no range of its own may be scaled.
 *
 * A fallback, not a policy. A widget that declares its own min and max is held to
 * those, and those are the same numbers the editor's resize handle stops at and
 * the renderer's bound is raised to, so what the widget draws and what the editor
 * says it may draw cannot come apart.
 */
private const val FALLBACK_MIN_SCALE = 0.4f
private const val FALLBACK_MAX_SCALE = 4f

/**
 * Draws a widget at its reference size, or scaled into the footprint when it has
 * been given one.
 *
 * The widget does NOT grow to fill whatever space is available. Its reference
 * size is the size it looks correct at, and that is what it draws at until
 * somebody places it at a size of their own. Then the footprint IS the size and
 * the content scales to it.
 *
 * ## Where the reference comes from
 *
 * The widget's own declaration, read from [LocalWidgetSizing]: `prefWidth` and
 * `prefHeight` on its `@Widget`. It used to be a pair of literals at the call
 * site, which was the only place the number existed, so the palette could not
 * show the footprint a drop was about to take and the editor could not say where
 * a resize handle was allowed to stop. Naming it in the annotation puts it where
 * everyone can read it; passing it here as well would be the same number written
 * twice, so the parameters default to the declaration and are for a caller with
 * no descriptor to read.
 *
 * ## Bounded is not the same as chosen
 *
 * This used to scale whenever both axes arrived bounded, which reads as "I am in
 * a cell" and is not what the constraint means. A Column inside a slot that fills
 * its surface hands each child the whole remaining height, so the clock in an
 * ordinary vertical slot measured its 200 by 230 against a thousand points of
 * leftover, hit the ceiling and drew a 140dp dial across 2974 points, pushing
 * every widget under it off the pane. Measured in the running launcher, not
 * reasoned about.
 *
 * So the question it asks is [LocalWidgetFootprintDp], which the renderer fills
 * in from the widget's own stored size and leaves at zero when nothing named one.
 */
@Composable
fun AdaptiveWidget(
    referenceWidth: Dp = LocalWidgetSizing.current.prefWidth.dp,
    referenceHeight: Dp = LocalWidgetSizing.current.prefHeight.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    val sizing = LocalWidgetSizing.current
    val footprint = LocalWidgetFootprintDp.current
    // A widget with no reference has nothing to scale against, so it draws the way
    // any other composable does: at whatever its content comes to. That is the
    // case for a widget rendered outside a slot, and for one whose declaration
    // names no preferred size.
    val hasReference = referenceWidth > 0.dp && referenceHeight > 0.dp
    val placed = hasReference && footprint.width > 0f && footprint.height > 0f
    // Without a footprint the reference size is the whole answer, and pinning it
    // here is what keeps the content's own fillMaxSize from taking the slot.
    val sized = when {
        placed || !hasReference -> modifier
        else -> modifier.size(referenceWidth, referenceHeight)
    }
    BoxWithConstraints(sized) {
        val scale = if (placed && constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val range = scaleRange(sizing, referenceWidth.value, referenceHeight.value)
            minOf(maxWidth / referenceWidth, maxHeight / referenceHeight)
                .coerceIn(range.start, range.endInclusive)
        } else {
            1f
        }
        CompositionLocalProvider(LocalWidgetScale provides scale) {
            content(scale)
        }
    }
}

/**
 * How far this widget may be scaled, from what it declared.
 *
 * A declared floor is the scale at which the tighter of the two axes reaches it,
 * so neither axis is ever drawn under the size the widget said it needs; a
 * declared ceiling is the scale at which the first axis reaches it, for the same
 * reason in the other direction. An axis that declared nothing does not
 * constrain, and a widget that declared neither keeps the old fallbacks.
 */
private fun scaleRange(sizing: WidgetSizing, refW: Float, refH: Float): ClosedFloatingPointRange<Float> {
    if (refW <= 0f || refH <= 0f) return FALLBACK_MIN_SCALE..FALLBACK_MAX_SCALE
    val declaredMin = listOfNotNull(
        (sizing.minWidth / refW).takeIf { sizing.minWidth > 0 },
        (sizing.minHeight / refH).takeIf { sizing.minHeight > 0 },
    ).maxOrNull()
    val declaredMax = listOfNotNull(
        (sizing.maxWidth / refW).takeIf { sizing.maxWidth > 0 },
        (sizing.maxHeight / refH).takeIf { sizing.maxHeight > 0 },
    ).minOrNull()
    val min = declaredMin ?: FALLBACK_MIN_SCALE
    val max = declaredMax ?: FALLBACK_MAX_SCALE
    // An inverted pair cannot come from a declaration the processor accepted, but
    // it can come from one axis declaring a floor and the other a ceiling that
    // disagree once both are expressed against the reference. The ceiling wins,
    // for the reason it wins in WidgetSizing.holdWidth.
    return minOf(min, max)..max
}

// Scales a text style's font size by the widget scale. Identity at 1f so an
// unplaced widget is untouched.
fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale)
