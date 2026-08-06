package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the two playback surfaces actually look like, off-screen, across both
 * styles and both palettes. Sheets land under build/render for eyeballing; the
 * assertions pin the three things the surfaces were reported wrong on.
 *
 * The widgets pull their subject from Koin and the service registry, so what is
 * exercised here is the presentation -- [MusicPlayerCard] and
 * [PlaybackMiniControl] handed state directly.
 */
class PlaybackWidgetsRenderTest {

    private val width = 460
    private val height = 340

    private val track = TrackInfo(title = "Bus Stop", artist = "Jun Maeda", album = "Hikarizaka")
    private val file: Path = Path.of("/music/bus-stop.flac")

    private fun playing(fraction: Float, durationMs: Long = 200_000L) = PlaybackState.Playing(
        file       = file,
        positionMs = (durationMs * fraction).toLong(),
        durationMs = durationMs,
    )

    /** Accent captured out of the live theme, so the probe cannot drift from it. */
    private var progressAccent: Color = Color.Unspecified

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        style: StyleSpec,
        dark: Boolean,
        name: String,
        scale: Float = 1f,
        content: @Composable () -> Unit,
    ): Bitmap {
        val scene = ImageComposeScene(
            width   = (width * scale).toInt(),
            height  = (height * scale).toInt(),
            density = Density(scale),
        ) {
            NxTheme(useDarkTheme = dark, style = style) {
                CompositionLocalProvider(
                    LocalStyle provides style,
                    LocalStrings provides EnglishStrings,
                ) {
                    progressAccent = NxTheme.colors.progressAccent
                    Column(
                        modifier            = Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
        val image = scene.render()
        scene.close()
        val out = File("build/render").apply { mkdirs() }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(out, "playback-$name@${scale.toInt()}x.png").writeBytes(it) }
        return Bitmap.makeFromImage(image)
    }

    @Composable
    private fun Sheet() {
        MusicPlayerCard(
            heading     = "Music",
            state       = playing(0.35f),
            track       = track,
            volume      = 0.7f,
            onPick      = {},
            onPlayPause = {},
            onStop      = {},
            onVolume    = {},
        )
        PlaybackMiniControl(
            state       = playing(0.35f),
            track       = track,
            volume      = 0.7f,
            onPlayPause = {},
            onVolume    = {},
        )
    }

    @Test
    fun `both planes separate from the ground they float over`() {
        // The complaint these surfaces came from is that they floated on the
        // wallpaper with no plane under them. A near-black ground is the hardest
        // case: it is what the app shows with no wallpaper set.
        val cases = listOf(
            "celestia-dark" to (CelestiaStyle to true),
            "brut-dark" to (BrutStyle to true),
            "celestia-light" to (CelestiaStyle to false),
            "brut-light" to (BrutStyle to false),
        )
        for ((name, styling) in cases) {
            val (style, dark) = styling
            render(style, dark, name, scale = 2f) { Sheet() }
            val bmp = render(style, dark, name) { Sheet() }

            val card = bmp.getColor(bmp.width / 2, (bmp.height * 0.14f).toInt())
            val mini = bmp.getColor(bmp.width / 2, (bmp.height * 0.66f).toInt())
            val page = bmp.getColor(bmp.width - 4, bmp.height - 4)
            assertTrue(!near(card, page, tolerance = 6), "$name: the card body must not match the page: ${hex(card)} vs ${hex(page)}")
            assertTrue(!near(mini, page, tolerance = 6), "$name: the mini body must not match the page: ${hex(mini)} vs ${hex(page)}")
        }
    }

    @Test
    fun `the mini control measures the track, not the volume`() {
        // Volume held at zero so the only thing that can ink the progress accent
        // is the measure itself.
        fun accentPixels(fraction: Float, volume: Float, name: String): Int {
            val bmp = render(CelestiaStyle, true, name) {
                PlaybackMiniControl(
                    state       = playing(fraction),
                    track       = track,
                    volume      = volume,
                    onPlayPause = {},
                    onVolume    = {},
                )
            }
            var hits = 0
            for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    if (near(bmp.getColor(x, y), progressAccent.toArgbInt(), tolerance = 20)) hits++
                }
            }
            return hits
        }

        val early = accentPixels(0.1f, volume = 0f, name = "measure-10")
        val late = accentPixels(0.9f, volume = 0f, name = "measure-90")
        assertTrue(early > 0, "the measure must draw something at 10%")
        assertTrue(late > early, "more track must be inked at 90% than at 10%: $early -> $late")

        // The widget used to carry no measure at all, so its only bar was the
        // volume slider -- which idles at full and read as a track played to the
        // end. A full volume with the track at its start must stay near-empty.
        val idleAtFullVolume = accentPixels(0f, volume = 1f, name = "measure-idle")
        assertTrue(
            idleAtFullVolume < late / 10,
            "an untouched track must not read as a finished one: $idleAtFullVolume vs $late inked",
        )
    }

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun near(a: Int, b: Int, tolerance: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) < tolerance

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
