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
import kotlin.math.abs
import kotlin.test.Test
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

    private fun runBeat(dark: Boolean, name: String): Frames {
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
            // ~490, text fade to ~590, decay 590-740, wave ~665-1015.
            var t = 0L
            while (t < 200) { renderAt(t); t += 16 }
            val entry = captureAt(200)
            while (t < 800) { renderAt(t); t += 16 }
            val midWave = captureAt(800)
            while (t < 1300) { renderAt(t); t += 16 }
            val end = captureAt(1300)
            return Frames(entry, midWave, end)
        } finally {
            scene.close()
            tmp.toFile().deleteRecursively()
        }
    }

    private fun java.awt.image.BufferedImage.colorAt(x: Int, y: Int): Color = Color(getRGB(x, y))

    private fun close(a: Color, b: Color, tolerance: Float = 0.06f): Boolean =
        abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance && abs(a.blue - b.blue) < tolerance

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

        // Mid-wave: the front has cleared the origin (backdrop shows through),
        // the far corner is still veiled.
        assertTrue(close(midWave.colorAt(w / 2, originY), backdrop), "mid-wave: origin cleared to backdrop")
        assertTrue(close(midWave.colorAt(8, 8), pal.field), "mid-wave: far corner still veiled")

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
        assertTrue(close(midWave.colorAt(w / 2, originY), backdrop), "mid-wave: origin cleared")
        assertTrue(close(midWave.colorAt(8, 8), pal.field), "mid-wave: corner veiled")
        assertTrue(close(end.colorAt(w / 2, originY), backdrop), "end: cleared")
    }
}
