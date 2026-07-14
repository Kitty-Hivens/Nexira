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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import hivens.config.Branding
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
        }
        DebugControlPanel(
            state = state,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
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
