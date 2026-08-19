package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render of the screenshot grid at two pane widths. The images
 * themselves never load here -- there is no network and no loader -- which is the
 * point: what is being looked at is the geometry, the cells and their edges, and
 * an empty cell shows that better than a photograph would.
 */
class ImageGalleryRenderTest {

    private val media = List(5) { GalleryMedia.Image(thumb = "https://example.invalid/$it.png", full = "https://example.invalid/$it.png") }

    private fun render(width: Int, name: String) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = CelestiaStyle) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(24.dp)) {
                    ImageGallery(media = media)
                }
            }
        }
        try {
            var frameNanos = 0L
            repeat(6) { scene.render(frameNanos); frameNanos += 16_000_000L }
            val frame = scene.render(frameNanos)
            Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
        } finally {
            scene.close()
        }
        assertTrue(Files.size(out) > 0)
    }

    @Test fun `renders the grid in a wide pane`() = render(1100, "gallery-wide.png")

    @Test fun `renders the grid in a narrow pane`() = render(560, "gallery-narrow.png")
}
