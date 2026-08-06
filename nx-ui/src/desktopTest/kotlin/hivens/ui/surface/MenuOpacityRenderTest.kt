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
 * Isolated (no window / no compositor / no GPU) proof that `opaque = true` -- the
 * treatment [hivens.ui.nx.NxContextMenu] uses -- lets NOTHING bleed through the
 * surface body, on the dark palette (where the default body floor is 0.92). Renders
 * two Floating surfaces over a bright magenta ground and samples the centre of each:
 * the default surface tints magenta (bleed), the opaque one stays pure #2A2A2A.
 */
class MenuOpacityRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `opaque surface admits no background bleed on dark`() {
        val wPx = 600
        val hPx = 440
        val scene = ImageComposeScene(width = wPx, height = hPx, density = Density(2f)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides CelestiaStyle,
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFF00FF))) { // bright magenta ground
                    Column(Modifier.padding(20.dp)) {
                        // Default dark body (floor 0.92) -- background bleeds through.
                        NxSurface(NxSurfaceLevel.Floating, glass = false, opaque = false, shape = RoundedCornerShape(12.dp)) {
                            Box(Modifier.size(260.dp, 60.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        // Opaque body (the menu's treatment) -- no bleed.
                        NxSurface(NxSurfaceLevel.Floating, glass = false, opaque = true, shape = RoundedCornerShape(12.dp)) {
                            Box(Modifier.size(260.dp, 60.dp))
                        }
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()

        // Save the frame for eyeballing too, beside every other render sheet.
        val outDir = File("build/render")
        outDir.mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(outDir, "menu-opacity.png").writeBytes(it) }

        val bmp = Bitmap.makeFromImage(image)
        // Centres, density 2: Column pad 40px; first surface ~y100, second ~y260; x~300.
        val old = bmp.getColor(300, 100) // default (0.92)
        val new = bmp.getColor(300, 260) // opaque
        fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        val (or, og, ob) = rgb(old)
        val (nr, ng, nb) = rgb(new)
        println("MenuOpacityRenderTest: default(0.92)=RGB($or,$og,$ob)  opaque=RGB($nr,$ng,$nb)  (magenta ground = 255,0,255)")

        // Opaque surface: pure grey ~ #2A2A2A (42,42,42), no magenta -> R==B and G within a few of them.
        assertTrue(nr in 30..55 && ng in 30..55 && nb in 30..55, "opaque body not the grey surface tone: ($nr,$ng,$nb)")
        assertTrue(abs(nr - nb) <= 6 && abs(ng - nb) <= 6, "opaque body is colour-tinted -> background bled through: ($nr,$ng,$nb)")
        // Sanity: the default (non-opaque) body DOES show the magenta bleed, so the test is real.
        assertTrue(ob - og >= 10, "expected the default 0.92 body to bleed magenta (B>>G), got ($or,$og,$ob)")
    }
}
