package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

/**
 * Width breakpoints for per-container adaptivity. Measured against the
 * container's OWN available width (via [AdaptiveWidth] / BoxWithConstraints),
 * not the window: a widget in a narrow slot adapts even on a wide window, and
 * the server grid widens when the right rail is collapsed. A global window-size
 * class would miss both cases.
 */
enum class WidthClass { Compact, Medium, Expanded }

object Breakpoints {
    // Below this a surface drops to its single-column / pill layout.
    val Compact: Dp = 480.dp

    // Below this a two-column surface stacks into one column.
    val Medium: Dp = 720.dp
}

fun widthClassFor(width: Dp): WidthClass = when {
    width < Breakpoints.Compact -> WidthClass.Compact
    width < Breakpoints.Medium  -> WidthClass.Medium
    else                        -> WidthClass.Expanded
}

/**
 * BoxWithConstraints that hands its content the resolved [WidthClass] for the
 * box's own max width. Branch layouts off [widthClass]; [maxWidth] is there for
 * finer thresholds. The receiver is [BoxWithConstraintsScope], so content can
 * also read `maxHeight` (e.g. a paginator sizing its page by available height).
 */
@Composable
fun AdaptiveWidth(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(widthClass: WidthClass, maxWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        content(widthClassFor(maxWidth), maxWidth)
    }
}

@Preview
@Composable
private fun AdaptiveWidthDarkPreview() = AdaptiveWidths(dark = true)

@Preview
@Composable
private fun AdaptiveWidthLightPreview() = AdaptiveWidths(dark = false)

@Composable
private fun AdaptiveWidths(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(800.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (room in listOf(320.dp, 560.dp, 760.dp)) {
                AdaptiveWidth(Modifier.width(room).border(1.dp, NxInk.line).padding(8.dp)) { widthClass, available ->
                    Text("$widthClass at ${available.value.toInt()} dp", style = MaterialTheme.typography.bodySmall, color = NxInk.main)
                }
            }
        }
    }
}
