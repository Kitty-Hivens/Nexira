package hivens.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

private const val OUT = "/tmp/claude-1000/-home-haru-Nexira/1ebeb613-bcd8-4d59-a2b5-86143d0979df/scratchpad"

// Candidate brand seeds. The first is what the fixed palette accents with today,
// so it is the "no change of identity" option; the rest move the hue deliberately.
private val SEEDS = listOf(
    "current BB86FC" to 0xFFBB86FC.toInt(),
    "indigo 5E68C0" to 0xFF5E68C0.toInt(),
    "violet 7C4DFF" to 0xFF7C4DFF.toInt(),
    "teal 12B8A0" to 0xFF12B8A0.toInt(),
    "amber E08A2E" to 0xFFE08A2E.toInt(),
)

class TempSeedGrid {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun grid() {
        val cellW = 250
        val cellH = 232
        val cols = PaletteVariant.entries.size
        val rows = SEEDS.size
        val scene = ImageComposeScene(
            width = (cellW * cols + 40) * 2,
            height = (cellH * rows + 60) * 2,
            density = Density(2f),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF08080A)).padding(20.dp)) {
                Column {
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Box(Modifier.width(0.dp))
                        PaletteVariant.entries.forEach { v ->
                            Text(
                                v.name,
                                color = Color(0xFFBBBBC4),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(cellW.dp).padding(start = 6.dp),
                            )
                        }
                    }
                    SEEDS.forEach { (seedLabel, seed) ->
                        Row {
                            PaletteVariant.entries.forEach { variant ->
                                val c = generatedNxColors(
                                    DarkColorPalette,
                                    PaletteSpec(seedArgb = seed, dark = true, variant = variant),
                                )
                                CompositionLocalProvider(
                                    LocalNxColors provides c,
                                    LocalStyle provides CelestiaStyle,
                                ) {
                                    Cell(seedLabel, c, Modifier.width(cellW.dp).height(cellH.dp).padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        val img = scene.render()
        File(OUT).mkdirs()
        File(OUT, "seed-grid.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Composable
    private fun Cell(seedLabel: String, c: NxColors, modifier: Modifier) {
        Box(modifier.background(c.background, RoundedCornerShape(10.dp)).padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(seedLabel, color = c.textSecondary, fontSize = 10.sp)
                // The plane ladder -- what a real screen is built from.
                listOf(NxSurfaceLevel.Base, NxSurfaceLevel.Raised, NxSurfaceLevel.Floating).forEach { lvl ->
                    NxSurface(lvl, Modifier.fillMaxWidth().height(26.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lvl.name, color = c.textSecondary, fontSize = 10.sp)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(c.primary)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Play", color = c.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(c.secondary, c.tertiary, c.success, c.warnAccent, c.criticalAccent).forEach {
                        Box(Modifier.size(26.dp, 18.dp).clip(RoundedCornerShape(5.dp)).background(it))
                    }
                }
            }
        }
    }
}
