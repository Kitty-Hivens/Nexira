package hivens.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/** True on macOS, where caption buttons live at the LEFT (traffic-light side). */
val HOST_IS_MAC: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Custom caption buttons for the undecorated window: minimize, maximize/restore,
 * close. Glyphs are vector-drawn (not font icons) so they stay crisp and need no
 * font-subset entry. Close routes through [onClose] -- pass the SAME lambda the
 * window's onCloseRequest uses so tray-hide / chaos-dialog behavior is preserved.
 *
 * Placement (which end of the bar) is the caller's job; on macOS use [HOST_IS_MAC]
 * to put these on the left.
 */
@Composable
fun WindowControls(
    state: WindowState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tint = CelestiaTheme.colors.textSecondary
    val hover = CelestiaTheme.colors.textPrimary.copy(alpha = 0.12f)
    val closeHover = CelestiaTheme.colors.error
    val maximized = state.placement == WindowPlacement.Maximized

    Row(modifier) {
        CaptionButton(
            label = s.windowMinimize,
            tint = tint,
            hoverBg = hover,
            onClick = { state.isMinimized = true },
            glyph = { c ->
                drawLine(
                    color = c,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = strokePx(),
                    cap = StrokeCap.Round,
                )
            },
        )

        CaptionButton(
            label = if (maximized) s.windowRestore else s.windowMaximize,
            tint = tint,
            hoverBg = hover,
            onClick = { state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized },
            glyph = { c ->
                if (maximized) {
                    val o = size.minDimension * 0.24f
                    val side = size.minDimension - o
                    drawRect(c, Offset(o, 0f), Size(side, side), style = Stroke(strokePx()))
                    drawRect(c, Offset(0f, o), Size(side, side), style = Stroke(strokePx()))
                } else {
                    drawRect(c, Offset.Zero, Size(size.minDimension, size.minDimension), style = Stroke(strokePx()))
                }
            },
        )

        CaptionButton(
            label = s.windowClose,
            tint = tint,
            hoverBg = closeHover,
            onClick = onClose,
            glyph = { c ->
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokePx(), cap = StrokeCap.Round)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokePx(), cap = StrokeCap.Round)
            },
        )
    }
}

/** A 46x32 hover-highlighted hit target with a 10dp vector glyph centered. */
@Composable
private fun CaptionButton(
    label: String,
    tint: Color,
    hoverBg: Color,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(46.dp, 32.dp)
            .background(if (hovered) hoverBg else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(tint) }
    }
}

private fun DrawScope.strokePx(): Float = 1.2.dp.toPx()
