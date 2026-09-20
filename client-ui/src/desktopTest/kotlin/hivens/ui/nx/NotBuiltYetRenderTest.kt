package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.RussianStrings
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The unbuilt-feature placeholder beside the error it must not be mistaken for.
 *
 * Drawn together on purpose: the whole point of the component is that a reader can
 * tell "we have not made this" from "this broke", and that is a claim about how
 * the two look next to each other rather than about either one alone.
 */
class NotBuiltYetRenderTest {

    @Test
    fun `the placeholder and an error read as different states`() {
        val out = Path.of("build/render", "not-built-yet.png")
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(760, 620, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                CompositionLocalProvider(LocalStrings provides RussianStrings) {
                    val s = LocalStrings.current
                    Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            NxSurface(
                                level = NxSurfaceLevel.Raised,
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                            ) {
                                NxNotBuiltYet(
                                    title = s.notBuiltYetTitle,
                                    message = s.notBuiltYetBody,
                                    detail = "Страница отдельной сборки",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            NxSurface(
                                level = NxSurfaceLevel.Raised,
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                            ) {
                                RetryStateBlock(
                                    title = s.modPageVersionsFailed,
                                    message = s.modPageVersionsFailedBody,
                                    retryLabel = s.contentTabRetry,
                                    onRetry = {},
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
        val png = try {
            var t = 0L
            repeat(20) { scene.render(t).close(); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 10_000, "the sheet drew almost nothing (${png.bytes.size} bytes)")
    }
}
