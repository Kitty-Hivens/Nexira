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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The last two of the seven values have to reach pixels like the rest.
 *
 * Both were booleans before, decided together with the blur by a tier, so a plane
 * could not be lifted without also being frosted nor edged without being lifted. They
 * are numbers now, and a named zero has to be a decision rather than an absence.
 *
 * Measured as differences against the same scene without them, because a hairline is
 * one pixel wide and a cast shadow is a gradient: an absolute threshold on either
 * would be pinning the renderer's antialiasing rather than the parameter.
 */
class SurfaceEdgeRenderTest {

    @Test
    fun `a cast shadow darkens just outside the plane, and a named zero does not`() {
        val lifted = outsideDarkness(shadowDp = 10f)
        val flat = outsideDarkness(shadowDp = 0f)
        println("SurfaceEdgeRenderTest: shadow 10dp -> $lifted, named zero -> $flat")
        assertTrue(lifted > flat + 2, "a named elevation did not cast: $lifted against $flat")
        assertTrue(flat < 3, "a named zero still cast a shadow: $flat")
    }

    @Test
    fun `a hairline shows on the edge, and a named zero removes it`() {
        val edged = edgeDelta(borderWidthDp = null, hairline = true)
        val bare = edgeDelta(borderWidthDp = 0f, hairline = true)
        println("SurfaceEdgeRenderTest: hairline -> $edged, named zero -> $bare")
        assertTrue(edged > bare + 2, "the hairline did not draw: $edged against $bare")
    }

    @Test
    fun `a named width draws a thicker hairline than the default`() {
        val thin = edgeDelta(borderWidthDp = 1f, hairline = true)
        val thick = edgeDelta(borderWidthDp = 4f, hairline = true)
        println("SurfaceEdgeRenderTest: 1dp -> $thin, 4dp -> $thick")
        assertTrue(thick > thin, "width made no difference: $thin against $thick")
    }

    /** Mean darkening of the band just outside the plane, against the bare page. */
    private fun outsideDarkness(shadowDp: Float): Double {
        val withPlane = render("shadow-$shadowDp") { plate(shadowDp = shadowDp, borderWidthDp = 0f) }
        val bare = render("shadow-none") {}
        var sum = 0.0
        var n = 0
        for (y in (Y - 6) until Y) for (x in (X + 20) until (X + W - 20)) {
            sum += luminance(bare, x, y) - luminance(withPlane, x, y)
            n++
        }
        return sum / n
    }

    /** How far the plane's own edge departs from its interior. */
    private fun edgeDelta(borderWidthDp: Float?, hairline: Boolean): Double {
        val px = render("edge-${borderWidthDp ?: "default"}") {
            plate(shadowDp = 0f, borderWidthDp = borderWidthDp, hairline = hairline)
        }
        val inside = luminance(px, X + W / 2, Y + H / 2)
        var worst = 0.0
        for (dy in 0..3) {
            val d = abs(luminance(px, X + W / 2, Y + dy) - inside)
            if (d > worst) worst = d
        }
        return worst
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = SW, height = SH, density = Density(1f)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides CelestiaStyle,
            ) {
                Box(Modifier.fillMaxSize().background(PAGE)) { content() }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(OUT, "surface-edge-$name.png").writeBytes(it) }
        val bmp = Bitmap.makeFromImage(image)
        return IntArray(SW * SH) { bmp.getColor(it % SW, it / SW) }
    }

    @androidx.compose.runtime.Composable
    private fun plate(shadowDp: Float, borderWidthDp: Float?, hairline: Boolean = true) {
        NxSurface(
            level = NxSurfaceLevel.Floating,
            modifier = Modifier.offset(X.dp, Y.dp).size(W.dp, H.dp),
            shape = RoundedCornerShape(10.dp),
            glass = false,
            tier = FrostTier.Flat,
            hairline = hairline,
            borderWidthDp = borderWidthDp,
            shadowDp = shadowDp,
        ) {}
    }

    private fun luminance(px: IntArray, x: Int, y: Int): Double {
        val c = px[y * SW + x]
        return 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
    }

    private companion object {
        const val SW = 320
        const val SH = 220
        const val X = 60
        const val Y = 50
        const val W = 200
        const val H = 120
        const val OUT = "build/render"

        /** Mid grey, so a shadow has somewhere to darken into. */
        val PAGE = Color(0xFF808080)
    }
}
