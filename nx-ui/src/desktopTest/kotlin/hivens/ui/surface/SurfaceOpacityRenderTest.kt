package hivens.ui.surface

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxColors
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A named opacity has to reach the pixel.
 *
 * It did not. `bodyFloor` was applied as a clamp, so every surface drew at 0.92 on
 * dark and 1.0 on light no matter what any knob above it said -- which is why the
 * glass slider moved nothing and the layer meant to show the wallpaper through was
 * covered before it drew. Light was the worse half: alpha was refused outright.
 *
 * These render a surface over a bright magenta ground and read the body. Green is
 * the instrument: the ground has none and every ladder tone has plenty, so the
 * green channel falls exactly as far as the ground is allowed through.
 */
class SurfaceOpacityRenderTest {

    @Test
    fun `a named opacity lets the ground through on dark`() {
        val opaque = greenAt(DarkColorPalette, opacity = 1f)
        val half = greenAt(DarkColorPalette, opacity = 0.5f)
        assertTrue(opaque - half >= 10, "dark refused the alpha: opaque $opaque, half $half")
    }

    /**
     * The case the clamp refused. Whether a light plane can afford to be translucent
     * depends on what sits behind it and on what the widget buys legibility with; the
     * library can see neither, so it may default but must not decide.
     */
    @Test
    fun `a named opacity lets the ground through on light too`() {
        val opaque = greenAt(LightColorPalette, opacity = 1f)
        val half = greenAt(LightColorPalette, opacity = 0.5f)
        assertTrue(opaque - half >= 50, "light refused the alpha: opaque $opaque, half $half")
    }

    /** The default is unchanged: a surface that names nothing still draws solid on light. */
    @Test
    fun `light stays solid when no opacity is named`() {
        val named = greenAt(LightColorPalette, opacity = 1f)
        val default = greenAt(LightColorPalette, opacity = null)
        assertTrue(abs(named - default) <= 2, "light default moved: named $named, default $default")
    }

    /** Same for dark: unconfigured surfaces keep the floor they always had. */
    @Test
    fun `dark keeps its floor when no opacity is named`() {
        val floored = greenAt(DarkColorPalette, opacity = bodyFloor(dark = true))
        val default = greenAt(DarkColorPalette, opacity = null)
        assertTrue(abs(floored - default) <= 2, "dark default moved: floor $floored, default $default")
    }

    // Renders one surface at [opacity] over magenta and returns the green channel at
    // its centre. blurDp = 0f so nothing but the body is under test.
    @OptIn(ExperimentalComposeUiApi::class)
    private fun greenAt(palette: NxColors, opacity: Float?): Int {
        val scene = ImageComposeScene(width = W, height = H, density = Density(1f)) {
            CompositionLocalProvider(
                LocalNxColors provides palette,
                LocalStyle provides CelestiaStyle,
            ) {
                Box(Modifier.fillMaxSize().background(GROUND)) { Plate(opacity) }
            }
        }
        val image = scene.render()
        scene.close()
        val tag = if (palette === LightColorPalette) "light" else "dark"
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
            level = NxSurfaceLevel.Floating,
            modifier = Modifier.offset(PAD.dp, PAD.dp).size((W - 2 * PAD).dp, (H - 2 * PAD).dp),
            shape = RoundedCornerShape(12.dp),
            blurDp = 0f,
            hairline = false,
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
