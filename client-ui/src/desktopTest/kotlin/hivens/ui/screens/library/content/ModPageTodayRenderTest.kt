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
import hivens.core.api.dto.modrinth.ModrinthLicense
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * What the launcher shows about a mod TODAY, drawn so it can be put beside the
 * reference rather than described.
 *
 * Two surfaces, because there is no third: the details dialog an installed row
 * opens, and the browser result that has no page behind it at all. Neither is a
 * project page, and the point of the sheet is to make the gap visible at a
 * glance instead of arguing about it in prose.
 */
class ModPageTodayRenderTest {

    private val voxy = InstalledContent(
        kind        = ContentKind.Mod,
        fileName    = "voxy-0.3.7.jar",
        displayName = "voxy",
        version     = "0.3.7",
        description = "Light-weight low overhead LoD mod for minecraft capabile of rendering extreme render distances (for systems that support opengl 4.6).",
        enabled     = true,
        iconBytes   = null,
        sizeBytes   = 3_512_000,
        homepageUrl = "https://modrinth.com/mod/voxy",
        license     = "ARR",
        authors     = listOf("cortex"),
        dependencies = listOf("fabric-api", "sodium"),
    )

    private val project = ModrinthProject(
        id          = "wMDoUAIC",
        slug        = "voxy",
        title       = "voxy",
        projectType = "mod",
        description = "A Level of Detail rendering mod",
        license     = ModrinthLicense(id = "ARR", name = "All Rights Reserved"),
        categories  = listOf("optimization", "utility"),
    )

    private fun hit(id: String, title: String, description: String) =
        ModrinthSearchHit(projectId = id, slug = id, title = title, description = description)

    @Test
    fun `the details dialog an installed mod opens`() {
        render(1500, 940, "mod-today-details-dialog.png") {
            Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                ContentDetailsDialog(
                    content        = voxy,
                    resolveProject = { project },
                    onDismiss      = {},
                )
            }
        }
    }

    @Test
    fun `the browser rows that stand in for a page`() {
        render(1800, 700, "mod-today-browser-rows.png", Density(2f)) {
            Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                Column(
                    modifier            = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Caption("строка результата поиска: не установлено / ставится / стоит / не легло")
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
