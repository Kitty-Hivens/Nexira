package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

/** Renders the authored scene at a few pointer positions and breath phases. */
@OptIn(ExperimentalComposeUiApi::class)
class SceneRenderTest {

    @Test
    fun `renders the authored scene`() {
        val folder = System.getProperty("osusb.folder") ?: return
        val scene = runBlocking { loadScene(folder) }
        assertTrue(scene != null, "no scene loaded from $folder")

        val out = File("build/scene-frames").apply { mkdirs() }
        val props = AltraVitaProps(folder = folder)
        val pointer = mutableStateOf(Offset.Unspecified)
        val phase = mutableFloatStateOf(0f)

        val s = ImageComposeScene(1280, 720, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF07060A))) {
                Canvas(Modifier.fillMaxSize()) {
                    drawScene(scene, props, pointer.value, phase.floatValue, 0L)
                }
            }
        }
        var n = 0
        try {
            for ((name, p, ph) in listOf(
                Triple("centre", Offset.Unspecified, 0f),
                Triple("left", Offset(200f, 500f), 0.25f),
                Triple("right", Offset(1100f, 180f), 0.75f),
            )) {
                pointer.value = p
                phase.floatValue = ph
                val bytes = s.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: continue
                File(out, "scene-$name.png").writeBytes(bytes)
                n++
            }
        } finally {
            s.close()
        }
        println("wrote $n scene frames into ${out.absolutePath}")
        assertTrue(n == 3)
    }
}
