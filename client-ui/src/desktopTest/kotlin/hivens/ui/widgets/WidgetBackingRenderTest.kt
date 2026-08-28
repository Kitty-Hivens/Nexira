package hivens.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.widget.model.WidgetChrome
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The backing must not be a square behind a widget that rounded itself.
 *
 * It was. The clip was applied only when the instance named a corner, and the
 * default corner was zero, so turning the glass up on a widget drew a hard
 * rectangle behind its rounded card. The default now follows the active style's
 * card corner and the clip is unconditional.
 *
 * A bright magenta page reads through the corner the backing rounded away. Anything
 * else there means something was painted square.
 */
class WidgetBackingRenderTest {

    @Test
    fun `an untouched backing rounds to the style corner`() {
        val corner = cornerPixel(WidgetChrome(glassAlphaPct = 100))
        assertTrue(corner.isPage, "the default backing painted into its corner: $corner")
    }

    @Test
    fun `a backing at full glass still rounds`() {
        // The case from the bug report: glass to the top, corner never touched.
        val corner = cornerPixel(WidgetChrome(glassAlphaPct = 100, paddingDp = 0))
        assertTrue(corner.isPage, "full glass painted a square: $corner")
    }

    @Test
    fun `a named zero corner is still a square, because it was asked for`() {
        val corner = cornerPixel(WidgetChrome(glassAlphaPct = 100, cornerRadiusDp = 0))
        assertTrue(!corner.isPage, "an explicit zero corner should fill its corner: $corner")
    }

    /** The centre is always covered, so the page must not read there in any case. */
    @Test
    fun `the backing covers its own middle at full glass`() {
        val mid = pixel(WidgetChrome(glassAlphaPct = 100), X + W / 2, Y + H / 2)
        assertTrue(!mid.isPage, "full glass did not cover the middle: $mid")
    }

    private data class Px(val r: Int, val g: Int, val b: Int) {
        /** The page is pure magenta; every surface tone here is grey. */
        val isPage: Boolean get() = r > 200 && g < 60 && b > 200
        override fun toString() = "($r,$g,$b)"
    }

    // One pixel in. The style's card corner is 12dp, so the arc crosses the
    // diagonal around 3.5px from the corner; at 3px in the sample lands on the
    // antialiased edge and reads as a blend of both.
    private fun cornerPixel(chrome: WidgetChrome) = pixel(chrome, X + 1, Y + 1)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixel(chrome: WidgetChrome, px: Int, py: Int): Px {
        val scene = ImageComposeScene(width = SW, height = SH, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(PAGE)) {
                    Box(Modifier.offset(X.dp, Y.dp).size(W.dp, H.dp)) {
                        WidgetBacking(chrome) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(OUT, "widget-backing-${chrome.glassAlphaPct}-${chrome.cornerRadiusDp}.png").writeBytes(it) }
        val c = Bitmap.makeFromImage(image).getColor(px, py)
        val out = Px((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        println("WidgetBackingRenderTest: glass=${chrome.glassAlphaPct} corner=${chrome.cornerRadiusDp} at ($px,$py) -> $out")
        return out
    }

    private companion object {
        const val SW = 320
        const val SH = 200
        const val X = 60
        const val Y = 40
        const val W = 200
        const val H = 120
        const val OUT = "build/render"
        val PAGE = Color(0xFFFF00FF)
    }
}
