package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.BadgeStyleSpec
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Square badge shell, which is what the corner assertions contrast against the
 *  rounded default. */
private val SquareStyle = CelestiaStyle.copy(badgeStyle = BadgeStyleSpec.Square)

/** A style that asks for no motion -- the subject of the indeterminate-track test.
 *  Both were one retired second style, for two unrelated reasons. */
private val StillStyle = CelestiaStyle.copy(animationMultiplier = 0f)

/**
 * Reads a measurement out of the frame rather than asserting the PNG is not
 * empty. A non-empty file is true of a bar drawn at the wrong length, of one
 * that ignores the style axis, and of one that draws nothing but its track --
 * which is the whole reason nine render tests in the tree caught none of the
 * defects that reached the running app.
 *
 * What is measured is the run of accent pixels along a scanline. Progress is the
 * run's length; the corner is the difference between the run at the bar's top
 * edge and at its middle. Both survive a redesign of everything around them.
 */
class NxProgressBarRenderTest {

    private val width = 400
    private val barHeight = 12
    private val density = 1f

    /** Accent run along one scanline, in pixels. */
    private fun accentRun(style: StyleSpec, progress: Float?, row: Int): Int {
        val bmp = render(style, progress)
        val accent = DarkColorPalette.progressAccent
        var run = 0
        for (x in 0 until width) {
            if (isAccent(bmp.getColor(x, row), accent)) run++
        }
        return run
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(style: StyleSpec, progress: Float?): Bitmap {
        val scene = ImageComposeScene(width = width, height = barHeight, density = Density(density)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides style,
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                    NxProgressBar(progress = progress, height = barHeight.dp)
                }
            }
        }
        val image = scene.render()
        scene.close()
        return Bitmap.makeFromImage(image)
    }

    /** One scanline as ARGB. */
    private fun row(bmp: Bitmap, y: Int): List<Int> = (0 until width).map { bmp.getColor(it, y) }

    /** Same tone within antialiasing noise. */
    private fun near(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < 6 &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < 6 &&
            abs((a and 0xFF) - (b and 0xFF)) < 6

    /** Tolerant match: the fill is composited over the track and the ground. */
    private fun isAccent(argb: Int, accent: Color): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val ar = (accent.red * 255).toInt()
        val ag = (accent.green * 255).toInt()
        val ab = (accent.blue * 255).toInt()
        return abs(r - ar) < 26 && abs(g - ag) < 26 && abs(b - ab) < 26
    }

    @Test
    fun `the filled run tracks the value`() {
        val mid = barHeight / 2
        val at0 = accentRun(CelestiaStyle, 0f, mid)
        val at25 = accentRun(CelestiaStyle, 0.25f, mid)
        val at50 = accentRun(CelestiaStyle, 0.5f, mid)
        val at100 = accentRun(CelestiaStyle, 1f, mid)

        assertEquals(0, at0, "an empty bar must draw no fill at all")
        assertTrue(at25 < at50 && at50 < at100, "run must grow with the value: $at25 / $at50 / $at100")
        // Half the track, within a couple of pixels of antialiasing.
        assertTrue(abs(at50 - width / 2) <= 3, "50% should fill half the track, filled $at50 of $width")
        assertTrue(abs(at100 - width) <= 3, "100% should fill the track, filled $at100 of $width")
    }

    @Test
    fun `an unknown job under motion-off draws a still full track, not a percentage`() {
        val mid = barHeight / 2
        // Asserting "no partial accent run" would pass on a frame with nothing
        // drawn at all, which is the failure mode this whole file exists to
        // avoid. So measure two things that cannot both hold on an empty frame:
        // the busy row is uniform end to end, and it is not the bare track.
        val busy = row(render(StillStyle, null), mid)
        val track = row(render(StillStyle, 0f), mid)

        val inset = 3 // skip the antialiased end pixels
        val sample = busy.slice(inset until width - inset)
        assertTrue(
            sample.all { near(it, sample.first()) },
            "busy fill must be uniform, not a stalled percentage: found ${sample.distinct().size} tones",
        )
        assertTrue(
            !near(busy[width / 2], track[width / 2]),
            "busy state must differ from the bare track, both read ${busy[width / 2]}",
        )
    }

    @Test
    fun `the corner follows the style axis`() {
        val mid = barHeight / 2
        val top = 0

        val celestiaMid = accentRun(CelestiaStyle, 1f, mid)
        val celestiaTop = accentRun(CelestiaStyle, 1f, top)
        val squareMid = accentRun(SquareStyle, 1f, mid)
        val squareTop = accentRun(SquareStyle, 1f, top)

        // Round: the top scanline clips the corners, so it is shorter than the middle.
        assertTrue(
            celestiaTop < celestiaMid - 4,
            "Celestia should round the ends: top $celestiaTop vs middle $celestiaMid",
        )
        // Square: every scanline is the same length.
        assertTrue(
            abs(squareTop - squareMid) <= 2,
            "Brut should square the ends: top $squareTop vs middle $squareMid",
        )
    }
}
