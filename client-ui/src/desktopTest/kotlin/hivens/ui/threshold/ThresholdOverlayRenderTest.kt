package hivens.ui.threshold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.launcher.CrashReporter
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.platform.PlatformPaths
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic walk through the threshold's exit beat. The whole beat is
 * frame-clock driven by design (no wall-clock delay), so `scene.render(t)`
 * addresses exact moments: the readout is on screen from the first frames
 * (the always-shown contract), the radial wave has cleared the bar's center
 * while the far corner is still veiled at mid-wave, and nothing of the
 * overlay remains at the end. Probes sample canvas pixels only -- text
 * rasterization is font-load dependent and deliberately unasserted.
 */
class ThresholdOverlayRenderTest {

    private val backdrop = Color(0xFF336699)

    private fun readyOutcome(tmp: Path): BootOutcome.Ready {
        val paths = PlatformPaths("Linux", tmp, { null }, { null })
        return BootOutcome.Ready(LauncherBootstrap.Result(paths, null, CrashReporter(paths)))
    }

    private data class Frames(val entry: java.awt.image.BufferedImage, val midWave: java.awt.image.BufferedImage, val end: java.awt.image.BufferedImage)

    /**
     * The scene is built and driven on the AWT event thread, and must stay there.
     * Compose's `GlobalSnapshotManager` pumps apply-notifications on that thread
     * on desktop, process-wide and outside any scene. The overlay's exit gate is
     * a `snapshotFlow`, so its collector resumes from that pump -- inline, since
     * an `ImageComposeScene` composes under `Dispatchers.Unconfined` -- and goes
     * on to run composition work there. Driving `render` from the test's own
     * thread therefore puts two threads in one scene, and they take two locks in
     * opposite orders: `sendFrame` holds the frame clock's awaiter lock and asks
     * for the flush dispatcher's, while the resumed collector holds the
     * dispatcher's and asks for the awaiter's. It needs that exact interleaving,
     * so it read as a rare hang under CPU contention rather than a broken test.
     * Anything else composing a `snapshotFlow` off-screen inherits the hazard.
     */
    private fun runBeat(dark: Boolean, name: String): Frames {
        val out = arrayOfNulls<Result<Frames>>(1)
        SwingUtilities.invokeAndWait { out[0] = runCatching { beat(dark, name) } }
        return out[0]!!.getOrThrow()
    }

    private fun beat(dark: Boolean, name: String): Frames {
        val tmp = Files.createTempDirectory("threshold-render")
        val stage = MutableStateFlow(BootStage.Modules)
        val outcome = readyOutcome(tmp)
        val strings = stringsFor(AppLocale.fromTag("ru"))

        val width = 1280
        val height = 800
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(backdrop)) {
                ThresholdOverlay(
                    stageFlow = stage,
                    outcome   = outcome,
                    strings   = strings,
                    logsDir   = tmp,
                    dark      = dark,
                    onQuit    = {},
                    onDone    = {},
                )
            }
        }
        try {
            fun renderAt(ms: Long) = scene.render(ms * 1_000_000L)
            fun captureAt(ms: Long): java.awt.image.BufferedImage {
                val png = renderAt(ms).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
                Files.createDirectories(Path.of("build/render"))
                Files.write(Path.of("build/render", "threshold-$name-$ms.png"), png.bytes)
                return ImageIO.read(ByteArrayInputStream(png.bytes))
            }

            // Walk the frame clock in ~16ms steps; the beat needs continuous
            // frames for BarMotion ticks and the frame-accumulated hold.
            // Warm-boot timeline with the 0.995 snap: bar full ~370ms, hold to
            // ~490, exit fade + veil lift ~490-710.
            var t = 0L
            while (t < 200) { renderAt(t); t += 16 }
            val entry = captureAt(200)
            while (t < 560) { renderAt(t); t += 16 }
            val midWave = captureAt(560)
            while (t < 1000) { renderAt(t); t += 16 }
            val end = captureAt(1000)
            return Frames(entry, midWave, end)
        } finally {
            scene.close()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * A boot that failed, rendered once. Static by design: the overlay stops
     * pumping frames on failure, so one render is the whole screen.
     */
    private fun failedFrame(): java.awt.image.BufferedImage {
        val out = arrayOfNulls<Result<java.awt.image.BufferedImage>>(1)
        SwingUtilities.invokeAndWait { out[0] = runCatching { renderFailed() } }
        return out[0]!!.getOrThrow()
    }

    private fun renderFailed(): java.awt.image.BufferedImage {
        val tmp = Files.createTempDirectory("threshold-render-failed")
        val scene = ImageComposeScene(FAIL_W, FAIL_H, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(backdrop)) {
                ThresholdOverlay(
                    stageFlow = MutableStateFlow(BootStage.Modules),
                    outcome   = BootOutcome.Failed(IllegalStateException("no definition found for a thing")),
                    strings   = stringsFor(AppLocale.fromTag("ru")),
                    logsDir   = tmp,
                    dark      = true,
                    onQuit    = {},
                    onDone    = {},
                )
            }
        }
        try {
            // The entry fade is an Animatable on the frame clock, so a single render
            // leaves it near zero and the readout is not drawn at all -- whatever the
            // failure branch decides. Walk the clock first, the way the beat does,
            // or the assertion below passes on an empty screen.
            var ms = 0L
            while (ms < 200) { scene.render(ms * 1_000_000L).close(); ms += 16 }
            val png = scene.render(200L * 1_000_000L).encodeToData(EncodedImageFormat.PNG)
                ?: error("PNG encode failed")
            Files.createDirectories(Path.of("build/render"))
            Files.write(Path.of("build/render", "threshold-failed.png"), png.bytes)
            return ImageIO.read(ByteArrayInputStream(png.bytes))
        } finally {
            scene.close()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a failed boot draws no progress bar`() {
        // It used to. The segments were suppressed and the frame was not, so the
        // screen that says the launcher did not start carried an empty loading bar
        // underneath, which is the one thing on it the eye goes to first and the
        // one thing on it that cannot mean anything.
        val img = failedFrame()
        // Against the veil, not the scene behind it: a boot that has not finished
        // keeps the field fully opaque, so the whole screen is the field colour and
        // anything drawn is a departure from it. The bar's own band, sampled across
        // the width; the error panel sits above it by construction, the readout
        // column ending two units short of the bar.
        // Every pixel of the row, not a grid over it. The frame is four bands and
        // at the bar's vertical centre only the two upright ones exist, six pixels
        // each; a sampling grid with any stride worth the name walks straight
        // between them and reports a clean row either way.
        val barCentreY = (FAIL_H * 0.62f).toInt()
        var painted = 0
        for (x in 0 until FAIL_W) {
            if (!close(img.colorAt(x, barCentreY), FIELD_DARK)) painted++
        }
        assertEquals(0, painted, "the bar band is not clear: $painted of $FAIL_W pixels are painted")
    }

    @Test
    fun `a failed boot still says what went wrong`() {
        // The other half of the same assertion: hiding the bar must not have taken
        // the panel with it.
        val img = failedFrame()
        var painted = 0
        for (gy in 8..13) {
            for (gx in 1..63) {
                if (!close(img.colorAt(gx * FAIL_W / 64, gy * FAIL_H / 20), FIELD_DARK)) painted++
            }
        }
        assertTrue(painted > 0, "nothing is drawn where the error panel belongs")
    }

    private fun java.awt.image.BufferedImage.colorAt(x: Int, y: Int): Color = Color(getRGB(x, y))

    private fun close(a: Color, b: Color, tolerance: Float = 0.06f): Boolean =
        abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance && abs(a.blue - b.blue) < tolerance

    /** Fraction of a sampled grid showing the backdrop (away from the bar's own pixels). */
    private fun clearRatio(img: java.awt.image.BufferedImage, w: Int, h: Int): Float {
        var cleared = 0
        var total = 0
        for (gx in 1..19) {
            for (gy in 1 until 8) {   // upper half only: keeps clear of the bar area
                total++
                if (close(img.colorAt(gx * w / 20, gy * h / 20), backdrop)) cleared++
            }
        }
        return cleared.toFloat() / total
    }

    @Test
    fun `dark palette walks the full beat`() {
        val pal = ThresholdPalette.Dark
        val (entry, midWave, end) = runBeat(dark = true, name = "dark")
        val w = 1280
        val h = 800
        val originY = (h * 0.62f).toInt()

        // Entry: the veil is opaque field everywhere off-bar, and the readout is
        // already sweeping -- the first segment's center is lit fill.
        assertTrue(close(entry.colorAt(8, 8), pal.field), "entry: corner is the opaque field")
        val firstSegX = w / 2 - 176 // inside the first lit segment (bar is 384px wide: 64 units * 6px)
        assertTrue(close(entry.colorAt(firstSegX, originY), pal.fill), "entry: first segment lit")

        // Mid-lift: the uniform dither has cleared part of the field -- the
        // sampled clear ratio sits strictly between "all veil" and "all shell".
        val ratio = clearRatio(midWave, w, h)
        assertTrue(ratio in 0.10f..0.90f, "mid-lift: partial dissolve, got $ratio")

        // End: nothing of the overlay remains anywhere.
        for ((x, y) in listOf(8 to 8, w - 8 to 8, 8 to h - 8, w - 8 to h - 8, w / 2 to originY)) {
            assertTrue(close(end.colorAt(x, y), backdrop), "end: ($x,$y) fully cleared")
        }
    }

    @Test
    fun `light palette walks the full beat`() {
        val pal = ThresholdPalette.Light
        val (entry, midWave, end) = runBeat(dark = false, name = "light")
        val w = 1280
        val h = 800
        val originY = (h * 0.62f).toInt()

        assertTrue(close(entry.colorAt(8, 8), pal.field), "entry: corner is the pale field")
        val ratio = clearRatio(midWave, w, h)
        assertTrue(ratio in 0.10f..0.90f, "mid-lift: partial dissolve, got $ratio")
        assertTrue(close(end.colorAt(w / 2, originY), backdrop), "end: cleared")
    }

    private companion object {
        const val FAIL_W = 1280
        const val FAIL_H = 800

        /** The dark palette's field, which is what an unfinished boot paints over everything. */
        val FIELD_DARK = Color(0.043f, 0.043f, 0.047f)
    }
}
