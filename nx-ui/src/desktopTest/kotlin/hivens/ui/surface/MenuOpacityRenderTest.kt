package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.CompositionLocalProvider
import hivens.ui.theme.LocalPlane
import hivens.ui.theme.Plane
import hivens.ui.theme.Backdrop
import hivens.ui.theme.NxColor

/**
 * Isolated (no window, no compositor, no GPU) proof of the materials: a popup, which
 * floats over arbitrary content, lets nothing through and is exactly the top step of
 * the ladder. Chrome over a picture is glass, and the picture shows through it. Chrome
 * over the bare page is solid in the step above the page, so it cannot read as page.
 */
class MenuOpacityRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a popup admits no bleed and glass does`() {
        val wPx = 600
        val hPx = 600
        val scene = ImageComposeScene(width = wPx, height = hPx, density = Density(2f)) {
            NxTheme(Themes.Celestia, dark = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFF00FF))) { // bright magenta ground
                    Column(Modifier.padding(20.dp)) {
                        // The magenta stands in for a wallpaper here.
                        CompositionLocalProvider(LocalPlane provides Plane(0, NxColor.page, Backdrop.Wallpaper)) {
                            NxSurface(SurfaceKind.Chrome, blurDp = 0f, shape = RoundedCornerShape(12.dp)) {
                                Box(Modifier.size(260.dp, 60.dp))
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        NxSurface(SurfaceKind.Popup, shadowDp = 0f, shape = RoundedCornerShape(12.dp)) {
                            Box(Modifier.size(260.dp, 60.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        NxSurface(SurfaceKind.Chrome, blurDp = 0f, shape = RoundedCornerShape(12.dp)) {
                            Box(Modifier.size(260.dp, 60.dp))
                        }
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()

        val outDir = File("build/render")
        outDir.mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(outDir, "menu-opacity.png").writeBytes(it) }

        val bmp = Bitmap.makeFromImage(image)
        // Centres at density 2: the column pads 40px, the surfaces sit near y100, y260 and y420, x300.
        val glass = bmp.getColor(300, 100)
        val popup = bmp.getColor(300, 260)
        val chromeOnPage = bmp.getColor(300, 420)
        fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        val (gr, gg, gb) = rgb(glass)
        println("MenuOpacityRenderTest: glass=RGB($gr,$gg,$gb)  popup=${"%08X".format(popup)}")

        val top = Themes.Celestia.dark.steps.last()
        assertEquals(top.toArgb(), popup, "the popup is not the top step")
        assertEquals(Themes.Celestia.dark.steps[1].toArgb(), chromeOnPage, "chrome over the page is not the step above it")
        assertTrue(gb - gg >= 40 && abs(gr - gb) <= 30, "glass hid the ground it should show: ($gr,$gg,$gb)")
    }
}
