package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Throwaway: the real transport glyphs against the ones I drew by hand. */
class GlyphCompareProbe {

    /** What I drew yesterday, kept verbatim for the comparison. */
    @Composable
    private fun HandDrawn(forward: Boolean) {
        val tint = NxTheme.colors.textPrimary
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val h = size.height * 0.78f
                val top = (size.height - h) / 2f
                val triW = w * 0.60f
                val barW = w * 0.13f
                val gap = w * 0.06f
                val total = triW + gap + barW
                val x0 = (w - total) / 2f
                val p = Path()
                if (forward) {
                    p.moveTo(x0, top); p.lineTo(x0 + triW, top + h / 2f); p.lineTo(x0, top + h); p.close()
                    drawPath(p, tint)
                    drawRect(tint, topLeft = Offset(x0 + triW + gap, top), size = GeomSize(barW, h))
                } else {
                    val xEnd = x0 + total
                    p.moveTo(xEnd, top); p.lineTo(xEnd - triW, top + h / 2f); p.lineTo(xEnd, top + h); p.close()
                    drawPath(p, tint)
                    drawRect(tint, topLeft = Offset(x0, top), size = GeomSize(barW, h))
                }
            }
        }
    }

    @Composable
    private fun Real(icon: hivens.ui.icons.IconKey) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Symbol(icon, null, tint = NxTheme.colors.textPrimary, fill = 1f, weight = 500, modifier = Modifier.size(22.dp))
        }
    }

    @Composable
    private fun Label(t: String) {
        Text(t, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun probe() {
        val d = 6f
        val scene = ImageComposeScene((230 * d).toInt(), (86 * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Column(
                    Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s12),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s10),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                        Label("real")
                        Real(NxIcon.SkipPrevious)
                        Real(NxIcon.Pause)
                        Real(NxIcon.SkipNext)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                        Label("mine")
                        HandDrawn(forward = false)
                        Real(NxIcon.Pause)
                        HandDrawn(forward = true)
                    }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/glyph-compare.png").writeBytes(it) }
    }
}