package hivens.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.window.WindowHost
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom caption buttons for the undecorated window: minimize, maximize/restore,
 * close. Glyphs are vector-drawn (not font icons) so they need no font-subset
 * entry. Close routes through [onClose] -- pass the SAME lambda the window's
 * onCloseRequest uses so tray-hide / chaos-dialog behavior is preserved.
 *
 * Each button takes the full height of the bar it sits in, as the system's own
 * caption buttons do, so its hover fill reads as a button and not as a patch
 * floating in the bar. The glyphs are laid on whole device pixels with one
 * stroke width for all three (see [glyphStroke]): a stroke of 1.2dp at 100%
 * fell between pixels and smeared into two grey lines, and a square stroked on
 * its outline drew heavier than the cross beside it.
 *
 * Every button acts through [host], and the maximise glyph shows the state the
 * system reports there (see [WindowHost]), never one the click assumed. Placement
 * (which end of the bar) is the caller's job; on macOS use [HOST_IS_MAC] to put
 * these on the left.
 *
 * Minimize and maximize show only when the window manager allows them for this
 * window ([capabilities]), so no button stands there doing nothing. Close is the
 * app's own and always shows.
 */
@Composable
fun WindowControls(
    host: WindowHost,
    capabilities: WindowCapabilities,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val maximized = host.status.collectAsState().value.maximized
    val ink = NxInk.main
    val plain = CaptionColours(
        glyph = ink.copy(alpha = GLYPH_ALPHA),
        glyphHover = ink,
        hover = ink.copy(alpha = HOVER_ALPHA),
        pressed = ink.copy(alpha = PRESSED_ALPHA),
    )
    val closeFill = NxColor.status(Status.Error)
    val close = CaptionColours(
        glyph = ink.copy(alpha = GLYPH_ALPHA),
        glyphHover = NxColor.on(closeFill),
        hover = closeFill,
        pressed = closeFill.copy(alpha = CLOSE_PRESSED_ALPHA),
    )

    Row(modifier.fillMaxHeight()) {
        if (capabilities.minimize) {
            CaptionButton(s.windowMinimize, plain, onClick = host::minimize) { c ->
                val stroke = glyphStroke()
                val y = snap(size.height / 2f, stroke)
                drawLine(c, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Butt)
            }
        }

        // The toolkit's answer stays in as well: on Windows and macOS it is the
        // only one there is.
        if (host.maximizeSupported && capabilities.maximize) {
            CaptionButton(if (maximized) s.windowRestore else s.windowMaximize, plain, onClick = host::toggleMaximize) { c ->
                val stroke = glyphStroke()
                if (maximized) {
                    // The window in front, and the edges of the one behind it that show.
                    val back = floor(size.width * RESTORE_OFFSET)
                    outline(c, Offset(0f, back), size.width - back, stroke)
                    val top = stroke / 2f
                    val right = size.width - stroke / 2f
                    drawLine(c, Offset(back, top), Offset(right, top), stroke, StrokeCap.Square)
                    drawLine(c, Offset(right, top), Offset(right, size.height - back), stroke, StrokeCap.Square)
                } else {
                    outline(c, Offset.Zero, size.width, stroke)
                }
            }
        }

        CaptionButton(s.windowClose, close, onClick = onClose) { c ->
            val stroke = glyphStroke()
            drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Butt)
            drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Butt)
        }
    }
}

private class CaptionColours(val glyph: Color, val glyphHover: Color, val hover: Color, val pressed: Color)

/** A full-height hit target with a 10dp glyph centred, filled while the pointer is on it. */
@Composable
private fun CaptionButton(
    label: String,
    colours: CaptionColours,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> colours.pressed
        hovered -> colours.hover
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .width(BUTTON_WIDTH)
            .fillMaxHeight()
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GLYPH_SIZE)) { glyph(if (hovered || pressed) colours.glyphHover else colours.glyph) }
    }
}

/** One device pixel per 100% of scale, and never less than one: the width every glyph is drawn with. */
private fun DrawScope.glyphStroke(): Float = max(1f, density.roundToInt().toFloat())

/** [v] moved to where a line of width [stroke] covers whole pixels. */
private fun snap(v: Float, stroke: Float): Float = floor(v - stroke / 2f) + stroke / 2f

/** A square of side [side] whose stroke lies inside it, starting at [topLeft]. */
private fun DrawScope.outline(c: Color, topLeft: Offset, side: Float, stroke: Float) {
    val h = stroke / 2f
    val l = topLeft.x + h
    val t = topLeft.y + h
    val r = topLeft.x + side - h
    val b = topLeft.y + side - h
    drawLine(c, Offset(l, t), Offset(r, t), stroke, StrokeCap.Square)
    drawLine(c, Offset(r, t), Offset(r, b), stroke, StrokeCap.Square)
    drawLine(c, Offset(r, b), Offset(l, b), stroke, StrokeCap.Square)
    drawLine(c, Offset(l, b), Offset(l, t), stroke, StrokeCap.Square)
}

private val BUTTON_WIDTH = 40.dp
private val GLYPH_SIZE = 10.dp
private const val RESTORE_OFFSET = 0.2f
private const val GLYPH_ALPHA = 0.82f
private const val HOVER_ALPHA = 0.10f
private const val PRESSED_ALPHA = 0.18f
private const val CLOSE_PRESSED_ALPHA = 0.8f
