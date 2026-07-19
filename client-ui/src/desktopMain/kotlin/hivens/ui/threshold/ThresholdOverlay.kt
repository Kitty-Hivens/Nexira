package hivens.ui.threshold

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font
import java.awt.Desktop
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.min

// Pixel-game boot readout: thick block frame with stepped corners, the fill
// made of discrete segments -- the reference is the classic 8/16-bit loading
// bar, not a hairline material progress. Two palettes, chosen by the peeked
// theme so a light-theme user is never blackout-flashed: dark = pixels of
// light in the dark; light = dark pixels on a pale field (the Game Boy way).
internal data class ThresholdPalette(
    val field: Color,
    val frame: Color,
    val fill: Color,
    val dim: Color,
) {
    companion object {
        val Dark  = ThresholdPalette(Color(0xFF0B0B0C), Color(0xFFE8E8E8), Color(0xFFFFFFFF), Color(0xFF8C8C8C))
        val Light = ThresholdPalette(Color(0xFFF2F1EC), Color(0xFF17171A), Color(0xFF000000), Color(0xFF6E6E6A))
    }
}

// The pixel quantum. Frame thickness, corner steps, segment gaps, the wave's
// dither cell and its jitter band are all multiples of this -- one knob keeps
// every element on the same virtual pixel grid.
private val UNIT = 6.dp

// The bar group sits at the golden fraction of the surface -- low enough to
// read staged, without the lower-third hole a 27" fullscreen would open.
private const val BAR_CENTER_Y = 0.62f

// The beat budget, all frame-clock driven (no wall-clock delay anywhere, so an
// off-screen scene addresses any frame of the beat deterministically). The
// readout ALWAYS shows: a warm boot plays the condensed column instead of
// skipping -- never a flicker, never a bare veil. The exit itself is DELIBERATELY
// minimal: full bar, one breath, then everything dissolves together in one quick
// dither lift -- the theatrical radial wave read as too much and was cut
// (DitherVeil still carries the wave mode should taste swing back).
private const val FLOOR_MS = 350f         // minimum readout screen time before the exit
private const val WARM_BOOT_MS = 400f     // Ready earlier than this = condensed hold
private const val HOLD_WARM_MS = 120f
private const val HOLD_COLD_MS = 180f
private const val EXIT_FADE_MS = 120
private const val VEIL_LIFT_MS = 220

/**
 * The boot-threshold screen, composed OVER the (not-yet or just-mounted)
 * shell inside the same window. Opaque while boot runs -- which also masks
 * the shell's expensive first composition -- then exits briefly and cleanly:
 * the bar completes, one breath, and the whole readout dissolves with the
 * dark veil in one quick dither lift, revealing the live shell beneath. A
 * luminance transition: only darkness is removed, nothing flashes. Calls
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
    dark: Boolean,
    onQuit: () -> Unit,
    onDone: () -> Unit,
) {
    val pal = if (dark) ThresholdPalette.Dark else ThresholdPalette.Light
    val stage by stageFlow.collectAsState()
    val currentOutcome by rememberUpdatedState(outcome)
    val ready  = outcome is BootOutcome.Ready
    val failed = outcome as? BootOutcome.Failed
    val pixelFont = FontFamily(Font(Res.font.press_start_2p))

    // Entry is a short fade, not a grace-gated appearance: the old grace window
    // skipped the readout entirely on a warm boot, which is exactly the
    // "boot animation sometimes missing" complaint.
    val entryAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entryAlpha.animateTo(1f, tween(120)) }

    val motion = remember { BarMotion() }
    var bar by remember { mutableStateOf(0f) }
    var overlayClockMs by remember { mutableStateOf(0f) }
    var readyAtMs by remember { mutableStateOf(-1f) }
    LaunchedEffect(Unit) {
        var first = -1L
        var last = -1L
        while (true) {
            // A failed boot is a static screen; keep pumping frames for it and
            // the whole error panel repaints at refresh rate forever.
            if (currentOutcome is BootOutcome.Failed) break
            withFrameNanos { now ->
                if (first < 0) first = now
                overlayClockMs = (now - first) / 1e6f
                if (readyAtMs < 0 && currentOutcome is BootOutcome.Ready) readyAtMs = overlayClockMs
                if (last >= 0) {
                    val dt = (now - last) / 1e6f
                    bar = if (currentOutcome is BootOutcome.Ready) {
                        // Boot finished: sweep to full fast; the beat gates on a
                        // truly full bar (BarMotion snaps the tail).
                        motion.tick(dt, target = 1f, ceiling = 1f, tauMs = 70f)
                    } else {
                        motion.tick(dt, target = stage.floor, ceiling = stage.ceiling)
                    }
                }
                last = now
            }
        }
    }

    // Exit drivers: the readout fades slightly ahead of the veil so the bar is
    // gone before the shell fully shows through, but both read as one motion.
    val exitFade = remember { Animatable(1f) }
    val veil = remember { Animatable(1f) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // Gate: a truly full bar AND the readout's floor of screen time -- a
        // warm boot still shows a complete (condensed) fill, never a flicker.
        snapshotFlow { bar >= 1f && overlayClockMs >= FLOOR_MS }.first { it }
        val condensed = readyAtMs >= 0f && readyAtMs <= WARM_BOOT_MS
        // The breath, accumulated on the frame clock (delay() would break
        // deterministic frame addressing in off-screen tests).
        val holdMs = if (condensed) HOLD_WARM_MS else HOLD_COLD_MS
        var holdStart = -1L
        while (true) {
            val done = withFrameNanos { now ->
                if (holdStart < 0) holdStart = now
                (now - holdStart) / 1e6f >= holdMs
            }
            if (done) break
        }
        launch { exitFade.animateTo(0f, tween(EXIT_FADE_MS)) }
        veil.animateTo(0f, tween(VEIL_LIFT_MS, easing = LinearOutSlowInEasing))
        onDone()
    }

    val percent by remember { derivedStateOf { (bar * 100).toInt() } }

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
            // first composition), lifted by the exit as a Bayer-dither dissolve
            // -- darkness leaves cell by cell on the pixel grid. Shader
            // unavailable -> plain alpha ramp. Per-frame brush creation is
            // LOAD-BEARING: the framework caches a ShaderBrush's shader keyed
            // only by size, so a remembered brush with mutated uniforms would
            // freeze the lift on its first frame.
            val lift = 1f - veil.value
            if (lift < 1f) {
                val ditherBrush = if (lift > 0f) DitherVeil.brush(lift, u, pal.field) else null
                when {
                    ditherBrush != null && lift > 0f -> drawRect(brush = ditherBrush)
                    lift > 0f                        -> drawRect(pal.field.copy(alpha = veil.value))
                    else                             -> drawRect(pal.field)
                }
            }

            // Readout: frame + segments follow the shared exit fade.
            val frameAlpha = entryAlpha.value * exitFade.value
            if (frameAlpha > 0f) {
                drawPixelFrame(frameRect, u, pal.frame.copy(alpha = 0.92f * frameAlpha))
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
                val alpha = if (failed != null) 0.25f * frameAlpha else frameAlpha
                for (i in 0 until lit) {
                    drawPixelSegment(
                        Rect(
                            Offset(inner.left + i * segStride, inner.top),
                            Size(3 * u, inner.height),
                        ),
                        step = u / 2f,
                        color = pal.fill.copy(alpha = alpha),
                    )
                }
            }
        }

        val labelRowSpace = barTop - UNIT * 2
        if (failed == null) {
            // Stage readout left + honest percent right, both on the bar's own
            // edges, pixel font. Percent shows the displayed (smoothed) value --
            // the same thing the segments show.
            val textAlpha = entryAlpha.value * exitFade.value
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
                        color      = pal.frame.copy(alpha = textAlpha),
                    )
                    Text(
                        text       = "$percent%",
                        fontFamily = pixelFont,
                        fontSize   = 16.sp,
                        color      = pal.frame.copy(alpha = textAlpha),
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
                    color      = pal.frame,
                )
                Text(
                    text       = failed.error.let { e ->
                        (e::class.simpleName ?: "error") + (e.message?.let { ": ${it.take(120)}" } ?: "")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    color      = pal.dim,
                    modifier   = Modifier.padding(top = 10.dp, bottom = 20.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(UNIT * 2)) {
                    ThresholdButton(strings.thresholdOpenLogs.lowercase(), pixelFont, pal) {
                        thread(isDaemon = true) {
                            runCatching { Desktop.getDesktop().open(logsDir.toFile()) }
                        }
                    }
                    ThresholdButton(strings.thresholdQuit.lowercase(), pixelFont, pal, onClick = onQuit)
                }
            }
        }
    }
}

@Composable
private fun ThresholdButton(label: String, font: FontFamily, pal: ThresholdPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .drawBehind {
                drawPixelFrame(Rect(Offset.Zero, size), (UNIT / 2).toPx(), pal.frame.copy(alpha = 0.85f))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = UNIT * 3, vertical = UNIT * 2),
    ) {
        Text(
            text       = label,
            fontFamily = font,
            fontSize   = 12.sp,
            color      = pal.frame,
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
