package hivens.ui.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The [Backdrop] layer has to blur what is actually beneath the surface.
 *
 * It never did. It was answered by redrawing the wallpaper at the surface's offset,
 * which is a narrower question than the one asked: a plane over another plane showed
 * the wallpaper it could not see rather than the plane it covered, and the redraw
 * itself was measured drawing nothing at all for any surface away from the window
 * origin.
 *
 * Stripes are the instrument. 8px bands have a large per-scanline spread and a blur
 * flattens it, so the spread inside the surface says whether anything was filtered.
 */
class BackdropBlurRenderTest {

    @Test
    fun `a backdrop layer flattens the stripes beneath it`() {
        val blurred = scanline(blurDp = 18f)
        val plain = scanline(blurDp = 0f)
        assertTrue(plain.spread > 60, "the control is not striped, the test proves nothing: ${plain.spread}")
        assertTrue(
            blurred.spread < plain.spread / 4,
            "backdrop did not filter: spread ${blurred.spread} against a control of ${plain.spread}",
        )
    }

    /**
     * The case the old answer could not reach. A coloured plate sits between the
     * stripes and the surface; the surface must take the plate's colour, because
     * that is what is behind it.
     */
    @Test
    fun `a backdrop layer blurs the plane beneath it, not the page`() {
        val over = scanline(blurDp = 18f, plate = PLATE)
        // The plate is pure green; the striped page has none. Anything but green
        // here means the surface reached past the plate to a page it cannot see.
        assertTrue(over.green > over.red + 40, "surface did not take the plate's colour: $over")
        assertTrue(over.spread < 20, "the plate should read flat under a blur: ${over.spread}")
    }

    private data class Sample(val spread: Double, val red: Int, val green: Int) {
        override fun toString() = "spread %.1f r=%d g=%d".format(spread, red, green)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scanline(blurDp: Float, plate: Color? = null): Sample {
        val scene = ImageComposeScene(width = W, height = H, density = Density(1f)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides CelestiaStyle,
            ) {
                Box(Modifier.fillMaxSize().drawBehind { stripes() }) {
                    if (plate != null) {
                        Box(Modifier.fillMaxSize().drawBehind { drawRect(plate) })
                    }
                    Plate(blurDp)
                }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        val name = "backdrop-blur-${blurDp.toInt()}${if (plate != null) "-over-plate" else ""}.png"
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(OUT, name).writeBytes(it) }

        val bmp = Bitmap.makeFromImage(image)
        val y = H / 2
        val xs = (SX + 30) until (SX + SW - 30)
        val lum = xs.map { x ->
            val c = bmp.getColor(x, y)
            0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        }
        val mean = lum.average()
        val spread = sqrt(lum.sumOf { (it - mean) * (it - mean) } / lum.size)
        val mid = bmp.getColor(SX + SW / 2, y)
        val s = Sample(spread, (mid shr 16) and 0xFF, (mid shr 8) and 0xFF)
        println("BackdropBlurRenderTest: blur=$blurDp plate=${plate != null} -> $s")
        return s
    }

    /** A translucent body so whatever the backdrop produced is visible through it. */
    @Composable
    private fun Plate(blurDp: Float) {
        NxSurface(
            level = NxSurfaceLevel.Base,
            modifier = Modifier.offset(SX.dp, SY.dp).size(SW.dp, SH.dp),
            shape = RoundedCornerShape(12.dp),
            tier = if (blurDp > 0f) FrostTier.Frosted else FrostTier.Flat,
            hairline = false,
            opacity = 0.15f,
        ) {}
    }

    private fun DrawScope.stripes() {
        var x = 0f
        var i = 0
        while (x < size.width) {
            drawRect(
                color = if (i % 2 == 0) Color.Black else Color.White,
                topLeft = Offset(x, 0f),
                size = Size(BAND, size.height),
            )
            x += BAND
            i++
        }
    }

    private companion object {
        const val W = 480
        const val H = 300
        const val SX = 120
        const val SY = 80
        const val SW = 240
        const val SH = 140
        const val BAND = 8f
        const val OUT = "build/render"

        /** Pure green: the striped page has none, so its presence is unambiguous. */
        val PLATE = Color(0xFF00A000)
    }
}
