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
import hivens.widget.model.SurfaceCorners
import hivens.widget.model.SurfaceShape
import hivens.widget.model.SurfaceSpec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The seven values have to reach the pixels through the widget path too.
 *
 * They did not, and not because any single one was broken. A widget that wanted a card
 * drew one itself while the kernel drew a second behind it, so the editor's slider
 * moved a plane the eye was not on: that is the whole of "the knob changes something
 * other than what I am looking at". There is one plane now, and the panel writes it.
 *
 * A bright magenta page reads through anything the surface did not cover.
 */
class WidgetSurfaceRenderTest {

    @Test
    fun `a literal fill is drawn exactly as written`() {
        val mid = pixel(SurfaceSpec(fill = "#FF00A000", opacity = 1f), X + W / 2, Y + H / 2)
        assertTrue(mid.g > 120 && mid.r < 40 && mid.b < 40, "a named colour did not reach the body: $mid")
    }

    @Test
    fun `a rung fill follows the palette rather than a literal`() {
        val raised = pixel(SurfaceSpec(fill = "raised", opacity = 1f), X + W / 2, Y + H / 2)
        val floating = pixel(SurfaceSpec(fill = "floating", opacity = 1f), X + W / 2, Y + H / 2)
        assertTrue(raised != floating, "two rungs resolved to one colour: $raised")
        assertTrue(raised.isGrey && floating.isGrey, "a rung produced a colour off the ladder: $raised / $floating")
    }

    @Test
    fun `opacity lets the page through`() {
        val solid = pixel(SurfaceSpec(fill = "#FF000000", opacity = 1f), X + W / 2, Y + H / 2)
        val half = pixel(SurfaceSpec(fill = "#FF000000", opacity = 0.5f), X + W / 2, Y + H / 2)
        assertTrue(half.r > solid.r + 80, "opacity did not reach the body: $solid against $half")
    }

    @Test
    fun `a corner is rounded away and the page shows through it`() {
        val spec = SurfaceSpec(fill = "#FF000000", opacity = 1f, shape = SurfaceShape(corners = SurfaceCorners(all = 24f)))
        val corner = pixel(spec, X + 2, Y + 2)
        assertTrue(corner.isPage, "the corner was painted into: $corner")
    }

    /** The case the old chrome could not express: rounded on one side, square on the other. */
    @Test
    fun `corners are independent of one another`() {
        val spec = SurfaceSpec(
            fill = "#FF000000",
            opacity = 1f,
            shape = SurfaceShape(corners = SurfaceCorners(topStart = 0f, bottomStart = 0f, topEnd = 24f, bottomEnd = 24f)),
        )
        val square = pixel(spec, X + 2, Y + 2)
        val rounded = pixel(spec, X + W - 3, Y + 2)
        assertTrue(!square.isPage, "the start corner should be square: $square")
        assertTrue(rounded.isPage, "the end corner should be rounded: $rounded")
    }

    @Test
    fun `padding insets the plane from the widget's box`() {
        val spec = SurfaceSpec(fill = "#FF000000", opacity = 1f, padding = hivens.widget.model.SurfaceInsets(all = 12f))
        val inset = pixel(spec, X + 4, Y + H / 2)
        assertTrue(inset.isPage, "padding did not inset the plane: $inset")
    }

    private data class Px(val r: Int, val g: Int, val b: Int) {
        val isPage: Boolean get() = r > 200 && g < 60 && b > 200
        val isGrey: Boolean get() = abs(r - g) < 12 && abs(g - b) < 12
        override fun toString() = "($r,$g,$b)"
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixel(spec: SurfaceSpec, px: Int, py: Int): Px {
        val scene = ImageComposeScene(width = SW, height = SH, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(PAGE)) {
                    Box(Modifier.offset(X.dp, Y.dp).size(W.dp, H.dp)) {
                        WidgetSurface(spec) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(OUT, "widget-surface-${spec.hashCode()}.png").writeBytes(it) }
        val c = Bitmap.makeFromImage(image).getColor(px, py)
        val out = Px((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        println("WidgetSurfaceRenderTest: $spec at ($px,$py) -> $out")
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
