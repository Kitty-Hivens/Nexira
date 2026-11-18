package hivens.ui.nx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.layers.Layer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.layerBody
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import kotlinx.coroutines.launch

/**
 * A panel that travels in from the window's right edge, over everything the window
 * holds: both rails, the title bar and the screen under it.
 *
 * Over the whole window rather than over the screen that opened it, because a
 * scrim that stops at the rails says the rails are still in play while the sheet
 * holds the keyboard and swallows the clicks. It is a modal layer of the window for
 * that reason: a layer draws above the window's own layout whichever screen asked
 * for it, and a menu opened inside the sheet draws above the sheet.
 *
 * The page stays readable beside it under a light scrim, which is the point of a
 * sheet over a dialog: what is being configured is still in view. [expanded] widens
 * it for work that wants the room, up to [expandedWidth], and both widths give way
 * to a window too narrow for them.
 *
 * [content] is handed the sheet's own close, which plays the way out before
 * [onDismissRequest] runs. Esc, a click on the scrim and that close all leave the
 * same way. A caller that removes the sheet without asking (the thing it configures
 * is gone) removes it at once, which is what that should look like.
 */
@Composable
fun NxSideSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    width: Dp = 640.dp,
    expandedWidth: Dp = 1120.dp,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    // Null where the look holds decoration still: the sheet then stands where it lands.
    val slide = Motion.decoration(Motion.panelSlide)
    val still = slide == null
    val scrimColour = NxColor.page
    // 0 is off screen, 1 is in place. One value drives the travel and the scrim, so
    // the page dims exactly as far as the sheet has come.
    val shown = remember { Animatable(if (still) 1f else 0f) }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = rememberUpdatedState(onDismissRequest)
    LaunchedEffect(Unit) { slide?.let { shown.animateTo(1f, it.of()) } }
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                slide?.let { shown.animateTo(0f, it.of()) }
                dismiss.value()
            }
        }
    }

    Layer(visible = true, modal = true, onDismissRequest = close) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrimColour.copy(alpha = SCRIM_ALPHA * shown.value)) }
                // The scrim runs under the system bars and the keyboard, the sheet
                // stays clear of them, as it did in a popup window of its own.
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val room = (maxWidth - EDGE * 2).coerceAtLeast(0.dp)
            val target = minOf(if (expanded) expandedWidth else width, room)
            val sheetWidth by animateDpAsState(target, (slide ?: Motion.tap).of(), label = "sheetWidth")
            NxSurface(
                kind     = SurfaceKind.Dialog,
                shape    = MaterialTheme.shapes.large,
                modifier = modifier
                    .align(Alignment.TopEnd)
                    .padding(EDGE)
                    .width(sheetWidth)
                    .fillMaxHeight()
                    // Translation alone, never alpha: a layer with alpha below one
                    // draws its content alone and anything inside that blurs what is
                    // behind it would find nothing there.
                    .graphicsLayer { translationX = (1f - shown.value) * (size.width + EDGE.toPx()) }
                    // The sheet itself, so a press on it is never one on the scrim
                    // beside it, which the layer reads as outside and closes.
                    .layerBody(),
            ) {
                Column(Modifier.fillMaxSize()) { content(close) }
            }
        }
    }
}

/** How far the sheet stands off the window's edges. */
private val EDGE = 8.dp

/** A light scrim: the page beside a sheet is meant to stay readable. */
private const val SCRIM_ALPHA = 0.45f

@Preview
@Composable
private fun NxSideSheetDarkPreview() = OpenSideSheet(dark = true)

@Preview
@Composable
private fun NxSideSheetLightPreview() = OpenSideSheet(dark = false)

/**
 * The sheet in a [LayerHost] that draws it in place, with motion reduced so it stands where
 * it lands instead of travelling in from the edge.
 */
@Composable
private fun OpenSideSheet(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark, reduceMotion = true) {
        LayerHost(rememberWindowLayers(), Modifier.size(640.dp, 400.dp).background(NxColor.page)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NxSectionHeader("Page behind")
                repeat(6) { Text("A line of the page beside the sheet", color = NxInk.main) }
            }
            NxSideSheet(onDismissRequest = {}, width = 320.dp) { close ->
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Pack settings",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = NxInk.main,
                        modifier   = Modifier.weight(1f),
                    )
                    NxIconButton(NxIcon.Close, "Close", onClick = close)
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NxToggle("Close on launch", checked = true, onCheckedChange = {})
                    NxSlider("Memory", value = 6f, range = 2f..16f, valueText = "6 GB", onValueChange = {})
                }
            }
        }
    }
}
