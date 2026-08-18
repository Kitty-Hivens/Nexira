package hivens.module.pixelplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.io.path.createDirectories
import java.nio.file.Path

/**
 * Renders the bar to a PNG so the look can be judged by eye rather than argued
 * about. Not an assertion: nothing here can tell whether it looks right.
 */
class PixelBarRenderProbe {

    private fun render(name: String, state: PlayerState) {
        val scene = ImageComposeScene(width = 900, height = 420, density = Density(2f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2B2F3A)), contentAlignment = Alignment.Center) {
                Box(Modifier.padding(16.dp)) {
                    PixelBar(state = state, cover = null, onToggle = {}, onPrev = {}, onNext = {}, onSeek = {})
                }
            }
        }
        val out: Path = Paths.get(System.getProperty("pixelplayer.probe.dir") ?: "build/probe")
        out.createDirectories()
        scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            Files.write(out.resolve("$name.png"), it)
        }
        scene.close()
    }

    @Test
    fun probe() {
        render(
            "playing",
            PlayerState(
                tracks = List(12) { Paths.get("/songs/track$it.mp3") },
                index = 3,
                playing = true,
                positionMs = 61_000,
                durationMs = 214_000,
                title = "Подготовка к ОГЭ по физике с нуля",
                artist = "初音ミク / ThirdWorldGamer",
            ),
        )
        render("empty", PlayerState())
    }
}
