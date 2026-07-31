package hivens.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.nx.NxFit
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Does the fallback actually engage? Asked in isolation because the surface that
 * uses it kept showing its full-width version and clipping, which means either
 * the measurement is wrong or the constraint never reaches it -- and only one of
 * those is NxFit's fault.
 *
 * Marked by colour rather than by pixel count: red is the preferred version,
 * green the compact one, so which branch ran is a fact rather than an inference.
 */
class NxFitTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun branchAt(allowed: Int, wants: Int): String {
        val scene = ImageComposeScene(width = 400, height = 40, density = Density(1f)) {
            Box(Modifier.background(Color.Black)) {
                Row(Modifier.width(allowed.dp)) {
                    NxFit(compact = { Box(Modifier.size(20.dp).background(Color.Green)) }) {
                        Box(Modifier.size(wants.dp, 20.dp).background(Color.Red))
                    }
                }
            }
        }
        val bmp = Bitmap.makeFromImage(scene.render())
        scene.close()
        var red = 0
        var green = 0
        for (x in 0 until 400) for (y in 0 until 40) {
            val c = bmp.getColor(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r > 150 && g < 90 && b < 90) red++
            if (g > 120 && r < 90 && b < 90) green++
        }
        return if (green > red) "compact" else "preferred"
    }

    @Test
    fun `content that fits keeps its full form`() {
        assertEquals("preferred", branchAt(allowed = 200, wants = 120))
    }

    @Test
    fun `content wider than the room falls back`() {
        assertEquals("compact", branchAt(allowed = 100, wants = 300))
    }
}
