package hivens.ui.screens.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.update.VersionChannel
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render of the version picker, which doubles as the guard on its
 * sizing rule: the window is a fraction of whatever it is dropped into, with no
 * dp ceiling, so a bigger host must produce a bigger window rather than the same
 * island in more empty space.
 *
 * The card is measured, not eyeballed. The scene paints a vivid backdrop, the
 * overlay scrims it, and the opaque card is the one horizontal run through the
 * middle that differs from the scrim -- so the assertion reads the drawn result
 * rather than the modifier that was supposed to produce it.
 */
class VersionPickerWindowRenderTest {

    private val versions = listOf(
        PickerVersion(
            id = "8",
            label = "0.1.8",
            channel = VersionChannel.Beta,
            publishedAt = "2026-07-25T23:47:00Z",
            runtimeLine = "Minecraft 1.12.2  Forge",
            latest = true,
        ),
        PickerVersion(
            id = "7",
            label = "0.1.7",
            channel = VersionChannel.Release,
            publishedAt = "2026-07-18T10:00:00Z",
            changelog = "# 0.1.7\n\nПочинена генерация чанков.",
            runtimeLine = "Minecraft 1.12.2  Forge",
            installed = true,
        ),
    ) + (0..40).map { i ->
        PickerVersion(
            id = "snap-$i",
            label = "SNAPSHOT-0.0.0-2026.06.%02d".format(i % 28 + 1),
            channel = VersionChannel.Alpha,
            runtimeLine = "Minecraft 1.12.2  Forge",
        )
    }

    /** Renders the picker over a pink field and returns the card's horizontal extent in px. */
    private fun renderAndMeasureCard(width: Int, height: Int, style: StyleSpec, name: String): Int {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                Box(Modifier.fillMaxSize().background(Color(0xFFE91E63))) {
                    VersionPickerWindow(
                        title = "Установка сборки",
                        packName = "Industrial",
                        packIconUrl = null,
                        versions = versions,
                        intentFor = { PickerIntent.Install },
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }
        val png = try {
            var frameNanos = 0L
            repeat(20) {
                scene.render(frameNanos)
                frameNanos += 16_000_000L
                Thread.sleep(10)
            }
            scene.render(frameNanos).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)

        val image = ImageIO.read(ByteArrayInputStream(png.bytes))
        return cardWidthAtMidHeight(image)
    }

    /**
     * Width of the contiguous non-scrim run across the vertical middle. The far
     * left column is scrim by construction (the card is centred and never fills
     * the host), so it serves as the reference sample.
     */
    private fun cardWidthAtMidHeight(image: BufferedImage): Int {
        val y = image.height / 2
        val scrim = image.getRGB(1, y)
        var first = -1
        var last = -1
        for (x in 0 until image.width) {
            if (!similar(image.getRGB(x, y), scrim)) {
                if (first < 0) first = x
                last = x
            }
        }
        assertTrue(first >= 0, "no card found over the scrim")
        return last - first + 1
    }

    private fun similar(a: Int, b: Int): Boolean {
        val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
        val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db < 24
    }

    @Test
    fun `card takes the host's width fraction and grows with the host`() {
        val fhd = renderAndMeasureCard(1920, 1080, CelestiaStyle, "version-picker-fhd.png")
        val twoK = renderAndMeasureCard(2560, 1440, CelestiaStyle, "version-picker-2k.png")

        // 88% of the host, within a corner-rounding pixel or two at the sampled row.
        assertTrue(abs(fhd - 1690) <= 8, "FHD card width $fhd, expected ~1690 (0.88 of 1920)")
        assertTrue(abs(twoK - 2253) <= 8, "2K card width $twoK, expected ~2253 (0.88 of 2560)")
        // The point of the rule: a bigger host is a bigger window, not more margin.
        assertTrue(twoK > fhd + 400, "card did not grow with the host: $fhd -> $twoK")
    }
}
