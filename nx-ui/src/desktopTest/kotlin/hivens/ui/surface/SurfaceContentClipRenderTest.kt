package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.LocalStyle
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A rounded surface has to round what is INSIDE it too.
 *
 * It did not. The plane was drawn by a layer compositor that took a list of layers and
 * gave each one a box of its own, all inside a box carrying the clip -- and content was
 * a SIBLING of that box, not a child. So a surface clipped its own fill to its shape
 * and let the widget in it paint square over the corners: a banner image, a photograph,
 * anything full-bleed. Widgets that noticed worked around it by clipping themselves.
 *
 * The layers are gone and the plane is one node, so the clip that shapes the body
 * shapes the content with it. A full-bleed green fill inside a 24dp corner is the
 * instrument: at the corner the page must still show.
 */
class SurfaceContentClipRenderTest {

    @Test
    fun `content is clipped to the surface shape`() {
        val corner = pixel(px = 2, py = 2)
        assertTrue(corner.isPage, "content painted over the rounded corner: $corner")
    }

    /** Without this the test above passes on a surface that drew nothing at all. */
    @Test
    fun `the content does draw where the shape allows it`() {
        val middle = pixel(px = W / 2, py = H / 2)
        assertTrue(middle.isContent, "the content did not draw, so the corner proves nothing: $middle")
    }

    private data class Px(val r: Int, val g: Int, val b: Int) {
        val isPage: Boolean get() = r > 200 && g < 60 && b > 200
        val isContent: Boolean get() = g > 120 && r < 60 && b < 60
        override fun toString() = "($r,$g,$b)"
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixel(px: Int, py: Int): Px {
        val scene = ImageComposeScene(width = W + 2 * M, height = H + 2 * M, density = Density(1f)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides CelestiaStyle,
            ) {
                Box(Modifier.fillMaxSize().background(PAGE)) {
                    NxSurface(
                        level = NxSurfaceLevel.Floating,
                        modifier = Modifier.offset(M.dp, M.dp).size(W.dp, H.dp),
                        shape = RoundedCornerShape(24.dp),
                        blurDp = 0f,
                        borderWidthDp = 0f,
                    ) {
                        Box(Modifier.fillMaxSize().background(CONTENT))
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(OUT, "surface-content-clip.png").writeBytes(it) }
        val c = Bitmap.makeFromImage(image).getColor(M + px, M + py)
        val out = Px((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        println("SurfaceContentClipRenderTest: at ($px,$py) -> $out")
        return out
    }

    private companion object {
        const val W = 200
        const val H = 120
        const val M = 20
        const val OUT = "build/render"

        /** No green in either, so the sample says which of the three it landed on. */
        val PAGE = Color(0xFFFF00FF)
        val CONTENT = Color(0xFF00C000)
    }
}
