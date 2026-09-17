package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the playable round mid-flight, and checks the beatmap actually parsed. */
@OptIn(ExperimentalComposeUiApi::class)
class RoundRenderTest {

    @Test
    fun `renders a round in play`() {
        val folder = System.getProperty("osusb.folder") ?: return
        val maps = loadBeatmaps(File(folder))
        assertTrue(maps.isNotEmpty(), "no difficulties parsed")
        for (m in maps) {
            println("%-12s notes=%4d  CS=%.1f AR=%.1f OD=%.1f  preempt=%.0fms r=%.1f  %s"
                .format(m.version, m.notes.size, m.circleSize, m.approachRate, m.overallDifficulty, m.preempt, m.radius, m.audio))
        }
        val round = runBlocking { loadRound(folder, 0, true) }
        assertTrue(round != null, "no round loaded")

        val out = File("build/round-frames").apply { mkdirs() }
        val now = mutableIntStateOf(0)
        val scene = ImageComposeScene(1280, 720, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF07060A))) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRound(round, now.intValue, Offset(700f, 400f), 42, 118, 96, 100, null)
                }
            }
        }
        var n = 0
        try {
            for (t in listOf(4_000, 30_000, 60_000)) {
                now.intValue = t
                val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: continue
                File(out, "round-%06d.png".format(t)).writeBytes(bytes)
                n++
            }
        } finally { scene.close() }
        println("wrote $n round frames into ${out.absolutePath}")
        assertTrue(n == 3)
    }
}
