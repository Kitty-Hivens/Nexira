package hivens.ui.threshold

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.press_start_2p
import hivens.ui.i18n.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.Font
import java.awt.Desktop
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.min

// Pixel-game boot readout: near-black field, thick block frame with stepped
// corners, the fill made of discrete segments -- the reference is the classic
// 8/16-bit loading bar, not a hairline material progress.
private val FieldBlack  = Color(0xFF0B0B0C)
private val FrameWhite  = Color(0xFFE8E8E8)
private val FillWhite   = Color(0xFFFFFFFF)

// The pixel quantum. Frame thickness, corner steps, segment gaps and the
// flood's chunky growth are all multiples of this -- one knob keeps every
// element on the same virtual pixel grid.
private val UNIT = 6.dp

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
 * bar completes, one breath, the bar's white floods the canvas (growing on
 * the pixel grid), the flood fades to reveal the live shell beneath. Calls
 * [onDone] when nothing is left to draw; the host removes it.
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
    val pixelFont = FontFamily(Font(Res.font.press_start_2p))

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

    // Exit is a LUMINANCE transition, not a color one: the readout fades
    // first, then the dark veil lifts off the already-live shell -- lights
    // coming up, no white flash on a night-black screen.
    val readoutFade = remember { Animatable(1f) }
    val veil        = remember { Animatable(1f) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (!contentShown) {
            // Fast path: the readout never appeared. Give the shell two frames
            // to land its first composition behind the black, then lift the
            // veil quickly instead of popping it.
            withFrameNanos {}
            withFrameNanos {}
            veil.animateTo(0f, tween(140, easing = LinearOutSlowInEasing))
            onDone()
            return@LaunchedEffect
        }
        // Exit beat: full bar, one breath, readout dims, darkness lifts.
        snapshotFlow { bar }.first { it >= 0.995f }
        delay(HOLD_MS)
        readoutFade.animateTo(0f, tween(160))
        veil.animateTo(0f, tween(420, easing = LinearOutSlowInEasing))
        onDone()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Bar geometry on the pixel grid: everything derives from UNIT.
        val barWidth  = min(64, floor((maxWidth.value * 0.5f) / UNIT.value).toInt()).let { (it - it % 2) * UNIT.value }.dp
        val barHeight = UNIT * 9
        val barTop    = maxHeight * BAR_CENTER_Y - barHeight / 2

        Canvas(Modifier.fillMaxSize()) {
            val u = UNIT.toPx()
            val frameRect = Rect(
                Offset((size.width - barWidth.toPx()) / 2f, barTop.toPx()),
                Size(barWidth.toPx(), barHeight.toPx()),
            )

            // The dark veil: fully opaque while boot runs (masking the shell's
            // first composition), lifted by the exit beat. The lift is a
            // Bayer-dither dissolve (the project's first shader) -- darkness
            // leaves cell by cell on the pixel grid; if the effect failed to
            // compile, a plain alpha ramp does the same job less prettily.
            val lift = 1f - veil.value
            val ditherBrush = if (lift > 0f) DitherVeil.brush(lift, u) else null
            when {
                lift <= 0f         -> drawRect(FieldBlack)
                ditherBrush != null -> drawRect(brush = ditherBrush)
                else                -> drawRect(FieldBlack.copy(alpha = veil.value))
            }

            val readout = contentAlpha * readoutFade.value
            if (readout > 0f) {
                drawPixelFrame(frameRect, u, FrameWhite.copy(alpha = 0.92f * readout))
                // Fill: discrete segments on the pixel grid, each 3 units wide
                // with a unit gap -- the bar loads chunk by chunk, never as a
                // smooth smear. Segment count derives from the inner width.
                val inner = Rect(
                    Offset(frameRect.left + 2 * u, frameRect.top + 2 * u),
                    Size(frameRect.width - 4 * u, frameRect.height - 4 * u),
                )
                val segStride = 4 * u
                val segCount = floor((inner.width + u) / segStride).toInt().coerceAtLeast(1)
                val fillFraction = if (failed != null) 0f else bar
                val lit = floor(fillFraction * segCount).toInt().coerceIn(0, segCount)
                val alpha = if (failed != null) 0.25f * readout else readout
                for (i in 0 until lit) {
                    drawPixelSegment(
                        Rect(
                            Offset(inner.left + i * segStride, inner.top),
                            Size(3 * u, inner.height),
                        ),
                        step = u / 2f,
                        color = FillWhite.copy(alpha = alpha),
                    )
                }
            }
        }

        val labelRowSpace = barTop - UNIT * 2
        if (failed == null) {
            // Stage readout left + honest percent right, both on the bar's own
            // edges, pixel font. Percent shows the displayed (smoothed) value --
            // the same thing the segments show.
            Box(
                modifier = Modifier.fillMaxWidth().height(labelRowSpace),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Row(
                    modifier = Modifier.width(barWidth),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text       = stageLabel(strings, stage).lowercase(),
                        fontFamily = pixelFont,
                        fontSize   = 16.sp,
                        color      = FrameWhite.copy(alpha = contentAlpha * readoutFade.value),
                    )
                    Text(
                        text       = "${(bar * 100).toInt()}%",
                        fontFamily = pixelFont,
                        fontSize   = 16.sp,
                        color      = FrameWhite.copy(alpha = contentAlpha * readoutFade.value),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().height(labelRowSpace),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text       = strings.thresholdErrorTitle.lowercase(),
                    fontFamily = pixelFont,
                    fontSize   = 18.sp,
                    color      = FrameWhite,
                )
                Text(
                    text       = failed.error.let { e ->
                        (e::class.simpleName ?: "error") + (e.message?.let { ": ${it.take(120)}" } ?: "")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    color      = Color(0xFF8C8C8C),
                    modifier   = Modifier.padding(top = 10.dp, bottom = 20.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(UNIT * 2)) {
                    ThresholdButton(strings.thresholdOpenLogs.lowercase(), pixelFont) {
                        thread(isDaemon = true) {
                            runCatching { Desktop.getDesktop().open(logsDir.toFile()) }
                        }
                    }
                    ThresholdButton(strings.thresholdQuit.lowercase(), pixelFont, onClick = onQuit)
                }
            }
        }
    }
}

@Composable
private fun ThresholdButton(label: String, font: FontFamily, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .drawBehind {
                drawPixelFrame(Rect(Offset.Zero, size), (UNIT / 2).toPx(), FrameWhite.copy(alpha = 0.85f))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = UNIT * 3, vertical = UNIT * 2),
    ) {
        Text(
            text       = label,
            fontFamily = font,
            fontSize   = 12.sp,
            color      = FrameWhite,
        )
    }
}

/**
 * The classic 8-bit block frame: four bands of [thickness], each inset by one
 * thickness at both ends, leaving the corner squares EMPTY -- the one-step
 * cut corner every pixel game rounds its rects with.
 */
private fun DrawScope.drawPixelFrame(r: Rect, thickness: Float, color: Color) {
    val t = thickness
    // top / bottom bands
    drawRect(color, Offset(r.left + t, r.top), Size(r.width - 2 * t, t))
    drawRect(color, Offset(r.left + t, r.bottom - t), Size(r.width - 2 * t, t))
    // left / right bands
    drawRect(color, Offset(r.left, r.top + t), Size(t, r.height - 2 * t))
    drawRect(color, Offset(r.right - t, r.top + t), Size(t, r.height - 2 * t))
}

/** A fill segment with stepped corners: full-height core, end columns inset by [step]. */
private fun DrawScope.drawPixelSegment(r: Rect, step: Float, color: Color) {
    drawRect(color, Offset(r.left + step, r.top), Size((r.width - 2 * step).coerceAtLeast(0f), r.height))
    drawRect(color, Offset(r.left, r.top + step), Size(step, (r.height - 2 * step).coerceAtLeast(0f)))
    drawRect(color, Offset(r.right - step, r.top + step), Size(step, (r.height - 2 * step).coerceAtLeast(0f)))
}

private fun stageLabel(s: AppStrings, stage: BootStage): String = when (stage) {
    BootStage.Files     -> s.thresholdStageFiles
    BootStage.Network   -> s.thresholdStageNetwork
    BootStage.Migration -> s.thresholdStageMigration
    BootStage.Modules   -> s.thresholdStageModules
    BootStage.Done      -> s.thresholdStageModules
}
