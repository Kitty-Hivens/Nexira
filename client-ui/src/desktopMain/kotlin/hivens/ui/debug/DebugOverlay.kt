package hivens.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import hivens.config.Branding
import hivens.ui.fx.DisintegrateBox
import hivens.ui.theme.NxTheme

// Fixed HUD palette: a debug overlay reads over any wallpaper/theme, so it is a
// deliberate dark chip with light text rather than themed surfaces. The panel
// accent still follows NxTheme.colors.primary so it matches the active style.
private val PANEL_BG = Color(0xF116171B)
private val PANEL_FG = Color(0xFFE6E6EA)
private val PANEL_DIM = Color(0xFF9A9AA4)
private val SLOT_STROKE = Color(0xFF4FC3F7)   // cyan -- slot outlines
private val WIDGET_STROKE = Color(0xFFFFB74D) // amber -- widget outlines
private val LABEL_BG = Color(0xC0000000)
private val LABEL_STYLE = TextStyle(fontSize = 9.sp, color = Color(0xFFECECEC))
private val RULER = Color(0xFF66BB6A)         // green -- spacing measures
private val RULER_STYLE = TextStyle(fontSize = 8.sp, color = Color(0xFFB9F6CA))

/**
 * Root of the dev UI-debug overlay. Draws nothing (and costs nothing) unless the
 * build is non-release AND the master toggle is on. The full-window layers are
 * interaction-passive -- only the corner control panel takes clicks, so app clicks
 * fall through everywhere else.
 */
@Composable
fun DebugOverlay(state: DebugOverlayState) {
    if (!state.available || !state.enabled) return
    val measurer = rememberTextMeasurer()
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInWindow().topLeft },
    ) {
        // Outlines + labels + sizes, drawn once from the reported bounds. Window
        // coords are shifted into overlay-local space; a draw-phase read of the
        // snapshot map redraws when any node moves.
        Canvas(Modifier.fillMaxSize()) {
            state.bounds.nodes.values.toList().forEach { node ->
                val show = when (node.kind) {
                    DebugNodeKind.Slot -> state.slotBounds
                    DebugNodeKind.Widget -> state.widgetBounds
                }
                if (!show) return@forEach
                val r = node.rect.translate(-overlayOrigin.x, -overlayOrigin.y)
                if (r.width <= 0f || r.height <= 0f) return@forEach
                val stroke = if (node.kind == DebugNodeKind.Slot) SLOT_STROKE else WIDGET_STROKE
                drawRect(stroke, topLeft = r.topLeft, size = r.size, style = Stroke(width = 1f))
                val text = "${node.label}  ${r.width.toInt()}x${r.height.toInt()}"
                val measured = measurer.measure(text, style = LABEL_STYLE)
                drawRect(
                    LABEL_BG,
                    topLeft = r.topLeft,
                    size = Size(measured.size.width + 6f, measured.size.height.toFloat()),
                )
                drawText(measured, topLeft = r.topLeft + Offset(3f, 0f))
            }
            // Spacing rulers: pairwise gaps between adjacent widget rects (each
            // adjacency drawn once -- the reverse pair yields a negative gap and is
            // skipped; gaps over the threshold are cross-layout distance, not rhythm).
            if (state.spacingRulers) {
                val wr = state.bounds.nodes.values.toList()
                    .asSequence()
                    .filter { it.kind == DebugNodeKind.Widget }
                    .map { it.rect.translate(-overlayOrigin.x, -overlayOrigin.y) }
                    .filter { it.width > 0f && it.height > 0f }
                    .toList()
                val thresh = 80f
                for (i in wr.indices) for (j in wr.indices) {
                    if (i == j) continue
                    val a = wr[i]; val b = wr[j]
                    val xOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                    if (xOverlap > 4f) {
                        val vGap = b.top - a.bottom
                        if (vGap > 0.5f && vGap <= thresh) {
                            val x = maxOf(a.left, b.left) + xOverlap / 2f
                            drawLine(RULER, Offset(x, a.bottom), Offset(x, b.top), strokeWidth = 1f)
                            val lbl = measurer.measure(vGap.toInt().toString(), style = RULER_STYLE)
                            drawText(lbl, topLeft = Offset(x + 2f, (a.bottom + b.top) / 2f - lbl.size.height / 2f))
                        }
                    }
                    val yOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                    if (yOverlap > 4f) {
                        val hGap = b.left - a.right
                        if (hGap > 0.5f && hGap <= thresh) {
                            val y = maxOf(a.top, b.top) + yOverlap / 2f
                            drawLine(RULER, Offset(a.right, y), Offset(b.left, y), strokeWidth = 1f)
                            val lbl = measurer.measure(hGap.toInt().toString(), style = RULER_STYLE)
                            drawText(lbl, topLeft = Offset((a.right + b.left) / 2f - lbl.size.width / 2f, y + 2f))
                        }
                    }
                }
            }
        }
        DebugControlPanel(
            state = state,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        if (state.perfHud) {
            PerfHud(Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        if (state.fxDemo) {
            FxDemoCard(Modifier.align(Alignment.Center))
        }
    }
}

// FX sandbox: a plain white accent card sliced apart by DisintegrateBox on tap and
// sprung back on the next tap -- a place to eyeball + tune the juice in a dev build.
@Composable
private fun FxDemoCard(modifier: Modifier) {
    var scattered by remember { mutableStateOf(false) }
    DisintegrateBox(
        scattered = scattered,
        modifier = modifier
            .size(260.dp, 340.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { scattered = !scattered },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Disintegrate FX", color = Color(0xFF16171B), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("tap: scatter / reassemble", color = Color(0xFF55606B), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            repeat(3) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE9ECF1)),
                )
            }
        }
    }
}

@Composable
private fun DebugControlPanel(state: DebugOverlayState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(190.dp)
            .background(PANEL_BG, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("UI DEBUG", color = NxTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(Branding.VERSION, color = PANEL_DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        FacetRow("Slot bounds", state.slotBounds) { state.slotBounds = it }
        FacetRow("Widget bounds", state.widgetBounds) { state.widgetBounds = it }
        FacetRow("Spacing rulers", state.spacingRulers) { state.spacingRulers = it }
        FacetRow("Recomposition", state.recomposition) { state.recomposition = it }
        FacetRow("Perf HUD", state.perfHud) { state.perfHud = it }
        FacetRow("FX: disintegrate", state.fxDemo) { state.fxDemo = it }
        Spacer(Modifier.height(6.dp))
        Text("F9 / \"uidebug\" toggles", color = PANEL_DIM, fontSize = 9.sp)
    }
}

@Composable
private fun FacetRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onToggle(!on) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (on) "[x]" else "[ ]",
            color = if (on) NxTheme.colors.primary else PANEL_DIM,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(label, color = PANEL_FG, fontSize = 11.sp)
    }
}

@Composable
private fun PerfHud(modifier: Modifier) {
    var fps by remember { mutableStateOf(0) }
    var frameMs10 by remember { mutableStateOf(0) } // tenths of a ms
    var heapUsed by remember { mutableStateOf(0L) }
    var heapMax by remember { mutableStateOf(0L) }
    var rss by remember { mutableStateOf(-1L) }
    val renderApi = remember { runCatching { System.getProperty("skiko.renderApi") }.getOrNull() ?: "?" }

    // Sample fps over a short burst, then idle for a beat: this keeps the HUD from
    // pinning the app at full framerate the whole time it is shown (continuous frames
    // also inflate the idle fps reading). Heap + RSS are read off the frame callback,
    // RSS on IO since it touches /proc.
    LaunchedEffect(Unit) {
        while (true) {
            var frames = 0
            var accumNs = 0L
            var last = 0L
            while (accumNs < 350_000_000L) {
                withFrameNanos { now ->
                    if (last != 0L) { accumNs += now - last; frames++ }
                    last = now
                }
            }
            if (frames > 0) {
                fps = (frames * 1_000_000_000.0 / accumNs).toInt()
                frameMs10 = ((accumNs / frames) / 100_000L).toInt()
            }
            val rt = Runtime.getRuntime()
            heapUsed = (rt.totalMemory() - rt.freeMemory()) shr 20
            heapMax = rt.maxMemory() shr 20
            rss = withContext(Dispatchers.IO) { readRssMb() }
            delay(500)
        }
    }

    Column(
        modifier = modifier
            .background(PANEL_BG, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        HudLine("$fps fps  ${frameMs10 / 10}.${frameMs10 % 10} ms")
        HudLine("heap $heapUsed / $heapMax MB")
        if (rss >= 0) HudLine("rss $rss MB")
        HudLine(renderApi)
    }
}

@Composable
private fun HudLine(text: String) {
    Text(text, color = PANEL_FG, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

// Linux resident set size (MB) from /proc; -1 elsewhere or on any read failure.
private fun readRssMb(): Long = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.shr(10) ?: -1L
    }
}.getOrDefault(-1L)
