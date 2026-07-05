package hivens.ui.threshold

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.i18n.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.awt.Desktop
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.math.min

// BIOS-brutal boot readout: near-black field, 1px square frame, white fill.
private val FieldBlack  = Color(0xFF0B0B0C)
private val FrameWhite  = Color(0xFFE8E8E8)
private val FillWhite   = Color(0xFFFFFFFF)
private val TextGray    = Color(0xFF8C8C8C)

// The bar group sits at the golden fraction of the surface -- low enough to
// read staged, without the lower-third hole a 27" fullscreen would open.
private const val BAR_CENTER_Y = 0.62f

// Anti-flash: a warm boot must not flicker a one-frame bar. Content appears
// only if boot is still running after the grace; a boot that beats it hands
// off black-to-shell without ever showing the readout.
private const val GRACE_MS = 250L
private const val HOLD_MS  = 180L

/**
 * The boot-threshold screen, composed OVER the (not-yet or just-mounted)
 * shell inside the same window. Opaque while boot runs -- which also masks
 * the shell's expensive first composition -- then plays the exit beat:
 * bar completes, one breath, the bar's white floods the canvas, the flood
 * fades to reveal the live shell beneath. Calls [onDone] when nothing is
 * left to draw; the host removes it from composition.
 *
 * Tier-0 by construction: no Koin, no NxTheme, no widget kernel. Strings
 * arrive pre-resolved from the settings peek.
 */
@Composable
fun ThresholdOverlay(
    stageFlow: StateFlow<BootStage>,
    outcome: BootOutcome?,
    strings: AppStrings,
    logsDir: Path,
    onQuit: () -> Unit,
    onDone: () -> Unit,
) {
    val stage by stageFlow.collectAsState()
    val currentOutcome by rememberUpdatedState(outcome)
    val ready  = outcome is BootOutcome.Ready
    val failed = outcome as? BootOutcome.Failed

    var contentShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(GRACE_MS)
        if (currentOutcome == null || currentOutcome is BootOutcome.Failed) contentShown = true
    }
    val contentAlpha by animateFloatAsState(if (contentShown) 1f else 0f, tween(120))

    val motion = remember { BarMotion() }
    var bar by remember { mutableStateOf(0f) }
    LaunchedEffect(contentShown) {
        if (!contentShown) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0) {
                    val dt = (now - last) / 1e6f
                    bar = if (currentOutcome is BootOutcome.Ready) {
                        // Boot finished: sweep to full fast, then the beat fires.
                        motion.tick(dt, target = 1f, ceiling = 1f, tauMs = 70f)
                    } else {
                        motion.tick(dt, target = stage.floor, ceiling = stage.ceiling)
                    }
                }
                last = now
            }
        }
    }

    val flood     = remember { Animatable(0f) }
    val whiteFade = remember { Animatable(1f) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (!contentShown) {
            // Fast path: the readout never appeared. Give the shell two frames
            // to land its first composition behind the black, then hand off.
            withFrameNanos {}
            withFrameNanos {}
            onDone()
            return@LaunchedEffect
        }
        // Exit beat: full bar, one breath, then the fill becomes the transition.
        snapshotFlow { bar }.first { it >= 0.995f }
        delay(HOLD_MS)
        flood.animateTo(1f, tween(300, easing = FastOutLinearInEasing))
        whiteFade.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
        onDone()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barWidth  = min(320f, maxWidth.value * 0.4f).dp
        val topAreaH  = maxHeight * BAR_CENTER_Y - 8.dp /* half bar */ - 4.dp /* gap+frame */ - 12.dp

        Canvas(Modifier.fillMaxSize()) {
            val f = flood.value
            val frameRect = barFrameRect(size, barWidth.toPx(), 16.dp.toPx())

            if (f < 1f) {
                drawRect(FieldBlack)
                // Frame: 1px stroke, square corners. The fill lives INSIDE a
                // 3px gap -- the gap is what makes the frame read as a frame.
                drawRect(
                    color   = FrameWhite.copy(alpha = 0.92f * contentAlpha),
                    topLeft = frameRect.topLeft,
                    size    = frameRect.size,
                    style   = Stroke(width = 1.dp.toPx()),
                )
                val gap = 4.dp.toPx()
                val innerW = (frameRect.width - gap * 2).coerceAtLeast(0f)
                val fillFraction = if (failed != null) 0f else bar
                drawRect(
                    color   = FillWhite.copy(alpha = if (failed != null) 0.25f else contentAlpha),
                    topLeft = Offset(frameRect.left + gap, frameRect.top + gap),
                    size    = Size(innerW * fillFraction, (frameRect.height - gap * 2).coerceAtLeast(0f)),
                )
            }
            if (f > 0f) {
                // The bar's light is the transition: its rect grows to the full
                // canvas, covers, then fades to reveal the shell beneath.
                val full = Rect(Offset.Zero, size)
                val r = lerpRect(frameRect, full, f)
                drawRect(
                    color   = FillWhite.copy(alpha = whiteFade.value),
                    topLeft = r.topLeft,
                    size    = r.size,
                )
            }
        }

        if (failed == null) {
            // Stage readout, bottom-anchored just above the frame.
            Box(
                modifier = Modifier.fillMaxWidth().height(topAreaH),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text       = stageLabel(strings, stage).lowercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    color      = TextGray.copy(alpha = contentAlpha),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().height(topAreaH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text       = strings.thresholdErrorTitle.lowercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color      = FrameWhite,
                )
                Text(
                    text       = failed.error.let { e ->
                        (e::class.simpleName ?: "error") + (e.message?.let { ": ${it.take(120)}" } ?: "")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    color      = TextGray,
                    modifier   = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThresholdButton(strings.thresholdOpenLogs.lowercase()) {
                        thread(isDaemon = true) {
                            runCatching { Desktop.getDesktop().open(logsDir.toFile()) }
                        }
                    }
                    ThresholdButton(strings.thresholdQuit.lowercase(), onClick = onQuit)
                }
            }
        }
    }
}

@Composable
private fun ThresholdButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, FrameWhite.copy(alpha = 0.76f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text       = label,
            fontFamily = FontFamily.Monospace,
            fontSize   = 12.sp,
            color      = FrameWhite,
        )
    }
}

private fun barFrameRect(canvas: Size, barWidthPx: Float, frameHeightPx: Float): Rect {
    val cx = canvas.width / 2f
    val cy = canvas.height * BAR_CENTER_Y
    return Rect(Offset(cx - barWidthPx / 2f, cy - frameHeightPx / 2f), Size(barWidthPx, frameHeightPx))
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left   = a.left   + (b.left   - a.left)   * t,
    top    = a.top    + (b.top    - a.top)    * t,
    right  = a.right  + (b.right  - a.right)  * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)

private fun stageLabel(s: AppStrings, stage: BootStage): String = when (stage) {
    BootStage.Files     -> s.thresholdStageFiles
    BootStage.Network   -> s.thresholdStageNetwork
    BootStage.Migration -> s.thresholdStageMigration
    BootStage.Modules   -> s.thresholdStageModules
    BootStage.Done      -> s.thresholdStageModules
}
