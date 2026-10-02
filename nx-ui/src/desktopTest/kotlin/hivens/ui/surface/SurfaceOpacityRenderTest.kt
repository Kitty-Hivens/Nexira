package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Themes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A named opacity has to reach the pixel, and an unnamed one is solid.
 *
 * A body used to default to 0.92 on dark, applied as a clamp, so every knob above it
 * moved nothing and the plane leaked whatever was under it. A body is opaque now
 * unless a caller names a number, and a named number is honoured on both themes.
 *
 * These render a surface over a bright magenta ground and read the body. Green is the
 * instrument: the ground has none and every ladder tone has plenty, so the green
 * channel falls exactly as far as the ground is allowed through.
 */
class SurfaceOpacityRenderTest {

    @Test
    fun `a named opacity lets the ground through on dark`() {
        val opaque = greenAt(dark = true, opacity = 1f)
        val half = greenAt(dark = true, opacity = 0.5f)
        assertTrue(opaque - half >= 10, "dark refused the alpha: opaque $opaque, half $half")
    }

    @Test
    fun `a named opacity lets the ground through on light too`() {
        val opaque = greenAt(dark = false, opacity = 1f)
        val half = greenAt(dark = false, opacity = 0.5f)
        assertTrue(opaque - half >= 50, "light refused the alpha: opaque $opaque, half $half")
    }

    @Test
    fun `a body that names no opacity is solid on both themes`() {
        for (dark in listOf(true, false)) {
            val named = greenAt(dark, opacity = 1f)
            val default = greenAt(dark, opacity = null)
            assertTrue(abs(named - default) <= 2, "default is not solid (dark=$dark): named $named, default $default")
        }
    }

    // Renders one surface at [opacity] over magenta and returns the green channel at
    // its centre.
    @OptIn(ExperimentalComposeUiApi::class)
    private fun greenAt(dark: Boolean, opacity: Float?): Int {
        val scene = ImageComposeScene(width = W, height = H, density = Density(1f)) {
            NxTheme(Themes.Celestia, dark = dark) {
                Box(Modifier.fillMaxSize().background(GROUND)) { Plate(opacity) }
            }
        }
        val image = scene.render()
        scene.close()
        val tag = if (dark) "dark" else "light"
        val name = "surface-opacity-$tag-${opacity ?: "default"}.png"
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(OUT, name).writeBytes(it) }
        val green = (Bitmap.makeFromImage(image).getColor(W / 2, H / 2) shr 8) and 0xFF
        println("SurfaceOpacityRenderTest: $tag opacity=${opacity ?: "default"} -> green $green")
        return green
    }

    @Composable
    private fun Plate(opacity: Float?) {
        NxSurface(
            kind = SurfaceKind.Panel,
            modifier = Modifier.offset(PAD.dp, PAD.dp).size((W - 2 * PAD).dp, (H - 2 * PAD).dp),
            shape = RoundedCornerShape(12.dp),
            borderWidthDp = 0f,
            opacity = opacity,
        ) {}
    }

    private companion object {
        const val W = 240
        const val H = 160
        const val PAD = 20
        const val OUT = "build/render"

        /** No green at all, so any green in the sample came from the body. */
        val GROUND = Color(0xFFFF00FF)
    }
}
