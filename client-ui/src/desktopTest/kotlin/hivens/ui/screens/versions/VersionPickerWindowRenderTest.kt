package hivens.ui.screens.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.update.VersionChannel
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Off-screen render of the version picker, which doubles as the guard on its
 * sizing rule.
 *
 * That rule was inverted deliberately, and this test says so. It used to be a
 * fraction of the host with no ceiling, on the reasoning that a bigger screen
 * should get a bigger window rather than the same island in more emptiness. What
 * that produced was eight builds inside a hall: the content does not grow with
 * the display, so the window growing with it only bought more nothing. The card
 * now takes the width it needs up to a ceiling and stops.
 *
 * The card is measured, not eyeballed. The scene paints a vivid backdrop, the
 * overlay scrims it, and the opaque card is the one region that differs from the
 * scrim, so the assertion reads the drawn result rather than the modifier that
 * was supposed to produce it.
 */
class VersionPickerWindowRenderTest {

    private val versions = listOf(
        PickerVersion(
            id = "8",
            label = "0.1.8",
            channel = VersionChannel.Beta,
            publishedAt = "2026-07-25T23:47:00Z",
            runtimeLine = "Minecraft 1.12.2  Forge",
            sizeLabel = "2,4 МБ",
            latest = true,
        ),
        PickerVersion(
            id = "7",
            label = "0.1.7",
            channel = VersionChannel.Release,
            publishedAt = "2026-07-18T10:00:00Z",
            changelog = "# 0.1.7\n\nПочинена генерация чанков.",
            runtimeLine = "Minecraft 1.12.2  Forge",
            sizeLabel = "2,3 МБ",
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

    /** Renders the picker over a pink field and returns the card's width in px. */
    private fun renderAndMeasureCard(width: Int, height: Int, name: String): Int {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFFE91E63))) {
                    VersionPickerWindow(
                        title = "Установка сборки",
                        packName = "Industrial",
                        packIcon = null,
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

        return cardWidth(ImageIO.read(ByteArrayInputStream(png.bytes)))
    }

    /**
     * Width of the card's bounding box. The card no longer fills the height, so a
     * single sampled row can land in the scrim above or below it; the widest run
     * anywhere in the image is the card.
     */
    private fun cardWidth(image: BufferedImage): Int {
        val scrim = image.getRGB(1, 1)
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (!similar(image.getRGB(x, y), scrim)) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        assertTrue(right >= 0, "no card found over the scrim")
        return right - left + 1
    }

    private fun similar(a: Int, b: Int): Boolean {
        val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
        val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db < 24
    }

    @Test
    fun `the card stops at its ceiling instead of following the display`() {
        val fhd = renderAndMeasureCard(1920, 1080, "version-picker-fhd.png")
        val twoK = renderAndMeasureCard(2560, 1440, "version-picker-2k.png")

        // 680dp at density 1, within a corner-rounding pixel or two.
        assertTrue(abs(fhd - 680) <= 6, "FHD card width $fhd, expected the 680 ceiling")
        // The point of the rule as it now stands: a third more display buys the
        // reader nothing here, because the list is the same length either way.
        assertEquals(fhd, twoK, "the card followed the display past its ceiling")
    }

    @Test
    fun `on a narrow window the card yields rather than overflowing`() {
        val narrow = renderAndMeasureCard(600, 900, "version-picker-narrow.png")

        // 94 percent of 600, so the card still clears the scrim on both sides.
        assertTrue(abs(narrow - 564) <= 8, "narrow card width $narrow, expected about 564")
        assertTrue(narrow < 600, "the card must not reach the window edges")
    }
}
