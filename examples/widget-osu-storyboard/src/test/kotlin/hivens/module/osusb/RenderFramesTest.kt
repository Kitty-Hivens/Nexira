package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders real frames of a real storyboard to PNG, off-screen.
 *
 * The parser test says the model is not empty. This one says the picture is not
 * empty, which is a different claim and the only one worth making about drawing
 * code. Skipped without `-Dosusb.folder=...`; writes into
 * `build/storyboard-frames/`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderFramesTest {

    @Test
    fun `renders frames from a real storyboard`() {
        val folder = System.getProperty("osusb.folder") ?: return
        val loaded = runBlocking { load(folder) }
        assertTrue(loaded != null, "nothing loaded from $folder")

        val out = File("build/storyboard-frames").apply { mkdirs() }
        val now = mutableIntStateOf(0)
        val state = SpriteState()

        val scene = ImageComposeScene(1280, 720, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Canvas(Modifier.fillMaxSize()) { drawStoryboard(loaded, now.intValue, state) }
            }
        }

        val times = listOf(1_000, 30_000, 52_500, 70_000, 98_500, 120_000, 150_000, 175_000)
        var written = 0
        try {
            for (t in times) {
                now.intValue = t
                val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: continue
                File(out, "frame-%06d.png".format(t)).writeBytes(bytes)
                written++
            }
        } finally {
            scene.close()
        }
        println("wrote $written frames into ${out.absolutePath}")
        assertTrue(written == times.size, "only $written of ${times.size} frames encoded")
    }
}
