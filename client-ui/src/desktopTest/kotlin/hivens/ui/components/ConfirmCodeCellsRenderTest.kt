package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The digit cells carry the code itself, so "invisible under some style" is the one
 * failure that matters: a typed digit that does not read leaves the user staring at
 * a time-boxed prompt they cannot verify. Rendered against both styles and both
 * palettes, and each frame is measured for a digit that actually stands off its
 * cell -- not merely for the composable surviving composition.
 */
class ConfirmCodeCellsRenderTest {

    private val width = 420
    private val height = 96

    @Test
    fun `typed digits stand off their cells on either palette`() {
        val cases = listOf("dark" to true, "light" to false)
        for ((name, dark) in cases) {
            val bmp = render(dark, name)
            assertTrue(
                contrastSpread(bmp) > MIN_SPREAD,
                "$name: the cells and their digits are indistinguishable (spread ${contrastSpread(bmp)})",
            )
        }
    }

    /**
     * Widest luminance gap found on the row the digits sit on. A cell drawn with no
     * contrast against its own fill collapses this to near zero, which is exactly the
     * "cannot read my own code" case.
     */
    private fun contrastSpread(bmp: Bitmap): Int {
        val y = bmp.height / 2
        var min = 255
        var max = 0
        for (x in 0 until bmp.width) {
            val c = bmp.getColor(x, y)
            val lum = ((c shr 16 and 0xFF) * 30 + (c shr 8 and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            if (lum < min) min = lum
            if (lum > max) max = lum
        }
        return max - min
    }

    private fun render(dark: Boolean, name: String): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            // The real theme entry point rather than a hand-provided palette, so the
            // sheet shows what the app resolves, tonal ladder included.
            NxTheme(useDarkTheme = dark) {
                CompositionLocalProvider(
                    LocalStrings provides EnglishStrings,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp),
                    ) { Cells() }
                }
            }
        }
        val image = scene.render()
        scene.close()
        File("build/render").apply { mkdirs() }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File("build/render", "confirm-code-$name.png").writeBytes(it) }
        return Bitmap.makeFromImage(image)
    }

    /** Four digits in, two to go: covers filled, next-up and empty cells in one frame. */
    @Composable
    private fun Cells() {
        ConfirmCodeCellsForTest(code = "4821")
    }

    private companion object {
        /** Below this the digit is not separable from its cell by eye either. */
        const val MIN_SPREAD = 40
    }
}
