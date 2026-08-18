package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * The menu row's shortcut had no gap because the popup is sized with
 * `IntrinsicSize.Max` and a weighted spacer measures as zero there. Rendered
 * rather than asserted: the defect is a distance, and a distance is seen.
 */
@Ignore("visual capture harness; run on demand")
class MenuHintProbe {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun probe() {
        val scene = ImageComposeScene(width = 760, height = 260, density = Density(2f)) {
            NxTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF2B2F3A)), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(16.dp).width(IntrinsicSize.Max)) {
                        Column {
                            NxMenuItem(label = "Редактировать компоновку", hint = "Ctrl+E") {}
                            NxMenuItem(label = "Сбросить поверхность") {}
                            NxMenuItem(label = "Открыть панель", hint = "Ctrl+N") {}
                        }
                    }
                }
            }
        }
        val out = Paths.get(System.getProperty("probe.dir") ?: "build/probe").also { it.createDirectories() }
        scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes?.let { Files.write(out.resolve("menu-hint.png"), it) }
        scene.close()
    }
}
