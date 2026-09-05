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
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LocalNxColors
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    private fun accentRun(progress: Float?, row: Int): Int {
        val bmp = render(progress)
        val accent = DarkColorPalette.progressAccent
        var run = 0
        for (x in 0 until width) {
            if (isAccent(bmp.getColor(x, row), accent)) run++
        }
        return run
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(progress: Float?): Bitmap {
        val scene = ImageComposeScene(width = width, height = barHeight, density = Density(density)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
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
        val at0 = accentRun(0f, mid)
        val at25 = accentRun(0.25f, mid)
        val at50 = accentRun(0.5f, mid)
        val at100 = accentRun(1f, mid)

        assertEquals(0, at0, "an empty bar must draw no fill at all")
        assertTrue(at25 < at50 && at50 < at100, "run must grow with the value: $at25 / $at50 / $at100")
        // Half the track, within a couple of pixels of antialiasing.
        assertTrue(abs(at50 - width / 2) <= 3, "50% should fill half the track, filled $at50 of $width")
        assertTrue(abs(at100 - width) <= 3, "100% should fill the track, filled $at100 of $width")
    }

    /** This used to contrast two styles' corners. There is one set of form tokens
     *  now, so what is left to check is that the bar rounds at all -- a flat end
     *  is what a lost corner token looks like from the outside. */
    @Test
    fun `the bar rounds its ends`() {
        val mid = accentRun(1f, barHeight / 2)
        val top = accentRun(1f, 0)
        assertTrue(top < mid - 4, "the ends should round: top $top vs middle $mid")
    }
}
