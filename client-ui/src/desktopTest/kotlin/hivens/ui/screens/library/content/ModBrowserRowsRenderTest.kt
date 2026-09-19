package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A search result in each of the four states it can be found in.
 *
 * The row is the only place in the app where an action reports on itself in
 * line: it is not installed, it is being fetched, it is in, or the fetch did not
 * land. Three of those look like an absence of the fourth unless they are drawn
 * side by side, and silence after a click reading as success is the failure this
 * row was rebuilt to stop.
 */
class ModBrowserRowsRenderTest {

    private fun hit(id: String, title: String, description: String) =
        ModrinthSearchHit(projectId = id, slug = id, title = title, description = description)

    @Test
    fun `a result row in each of its four states`() {
        render(1800, 700, "mod-browser-rows.png", Density(2f)) {
            Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                Column(
                    modifier            = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Caption("строка результата: не установлено / ставится / стоит / не легло")
                    ModResultRow(
                        hit       = hit("voxy", "voxy", "A Level of Detail rendering mod"),
                        installed = false, working = false, failed = false, onInstall = {},
                    )
                    ModResultRow(
                        hit       = hit("sodium", "Sodium", "A high-performance rendering engine replacement for Minecraft, which greatly improves frame rates and reduces micro-stutter."),
                        installed = false, working = true, failed = false, onInstall = {},
                    )
                    ModResultRow(
                        hit       = hit("iris", "Iris Shaders", "A modern shader pack loader for Minecraft intended to be compatible with existing OptiFine shader packs"),
                        installed = true, working = false, failed = false, onInstall = {},
                    )
                    ModResultRow(
                        hit       = hit("lithium", "Lithium", "No-compromises game logic optimization mod, useful for both singleplayer and multiplayer servers."),
                        installed = false, working = false, failed = true, onInstall = {},
                    )
                }
            }
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }

    private fun render(
        width: Int,
        height: Int,
        name: String,
        density: Density = Density(1f),
        content: @Composable () -> Unit,
    ) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = density) {
            NxTheme(useDarkTheme = true) { content() }
        }
        val png = try {
            var frameNanos = 0L
            repeat(14) {
                scene.render(frameNanos)
                frameNanos += 16_000_000L
            }
            scene.render(frameNanos).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
    }
}
