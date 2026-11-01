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
import androidx.compose.ui.window.WindowState
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * Custom caption buttons for the undecorated window: minimize, maximize/restore,
 * close. Glyphs are vector-drawn (not font icons) so they stay crisp and need no
 * font-subset entry. Close routes through [onClose] -- pass the SAME lambda the
 * window's onCloseRequest uses so tray-hide / chaos-dialog behavior is preserved.
 *
 * Maximize/restore is driven by [maximizer], not [WindowState.placement]: the
 * undecorated frame's AWT placement round-trip is unreliable on Windows, so the
 * glyph reads our own state (see [WindowMaximizer]). Placement (which end of the
 * bar) is the caller's job; on macOS use [HOST_IS_MAC] to put these on the left.
 *
 * Minimize and maximize show only when the window manager allows them for this
 * window ([capabilities]), so no button stands there doing nothing. Close is the
 * app's own and always shows.
 */
@Composable
fun WindowControls(
    state: WindowState,
    maximizer: WindowMaximizer,
    capabilities: WindowCapabilities,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tint = NxInk.quiet
    val hover = NxInk.main.copy(alpha = 0.12f)
    val closeHover = NxColor.status(Status.Error)
    val maximized = maximizer.maximized

    Row(modifier) {
        if (capabilities.minimize) {
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
        }

        // The toolkit's answer stays in as well: on Windows and macOS it is the
        // only one there is.
        if (maximizer.supported && capabilities.maximize) {
            CaptionButton(
                label = if (maximized) s.windowRestore else s.windowMaximize,
                tint = tint,
                hoverBg = hover,
                onClick = { maximizer.toggle() },
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
        }

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
