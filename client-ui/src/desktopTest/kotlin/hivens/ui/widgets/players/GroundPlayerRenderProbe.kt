package hivens.ui.widgets.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.audio.Waveform
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test

/**
 * Concept K on screen.
 *
 * Two things carry this shape and both can fail quietly. The ground has to be a
 * colour field rather than a recognisable picture, which is a question about how
 * far the blur goes and cannot be answered by reading the code; and the white
 * text has to survive whatever the cover happens to be, which is a question
 * about the darkening. Both are photographed here, including at the ends of the
 * dim range, because a card that is legible only on the sample artwork is a card
 * that looks finished and is not.
 *
 * A real cover is passed where one is configured, through the same property the
 * other probes read, since the stand-in is deliberately flat and a flat picture
 * blurs to nothing and proves nothing.
 */
class GroundPlayerRenderProbe {

    private val cover by lazy { ProbeSample.cover() }

    private val sample: Waveform = Waveform(
        FloatArray(512) { i ->
            val t = i / 512f
            val body = abs(sin(t * 22f)) * 0.5f + 0.4f
            if (t < 0.03f) 0.02f else body
        },
    )

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    private val track get() = TrackInfo(
        title = "Sacrifice",
        artist = "Taka feat. めらみぽっぷ",
        album = "追憶のサクラメント",
        artwork = cover,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, dark: Boolean, body: @androidx.compose.runtime.Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((wDp * d).toInt(), (hDp * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.ENGLISH) {
                NxTheme(useDarkTheme = dark) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                        contentAlignment = Alignment.TopCenter,
                    ) { body() }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-ground-$name.png").writeBytes(it)
        }
    }

    private @androidx.compose.runtime.Composable fun card(
        state: PlaybackState,
        info: TrackInfo?,
        dim: Float = 0.6f,
        queueSize: Int = 4,
    ) = GroundPlayerCard(
        state = state, track = info, waveform = sample, volume = 0.7f,
        repeat = RepeatMode.Off, queueSize = queueSize, dim = dim,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
    )

    /** The card in both palettes, and the darkening at the ends of its range. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("card-${if (dark) "dark" else "light"}", 400, 440, dark) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    // The concept's own value, then none, then the far end. If the
                    // first and the second look alike the darkening is not reaching
                    // the ground at all.
                    listOf(0.6f, 0f, 1f).forEach { dim ->
                        Box(Modifier.width(340.dp)) { card(playing(107_000L), track, dim) }
                    }
                }
            }
        }
    }

    /** No artwork, nothing loaded, and a file that would not open. */
    @Test
    fun states() {
        sheet("states", 400, 440, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Box(Modifier.width(340.dp)) {
                    card(playing(12_000L), TrackInfo("audio", null, null, null))
                }
                Box(Modifier.width(340.dp)) { card(PlaybackState.Idle, null, queueSize = 0) }
                Box(Modifier.width(340.dp)) {
                    card(
                        PlaybackState.Error(Paths.get("/music/broken.mp3"), hivens.ui.audio.AudioError.OpenFailed),
                        null,
                        queueSize = 0,
                    )
                }
            }
        }
    }

    /** The width sweep. One threshold here, the skips at 280. */
    @Test
    fun widths() {
        val widths = listOf(400, 340, 300, 280, 260, 220, 180)
        sheet("widths", 440, 1000, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                widths.forEach { w ->
                    Box(Modifier.width(w.dp)) { card(playing(107_000L), track) }
                }
            }
        }
    }
}
