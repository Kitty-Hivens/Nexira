package hivens.ui.widgets.sample.players

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
 * Concept E on screen, which is the only way to see whether it IS concept E.
 *
 * The identity of this shape is that the envelope carries both jobs at once, the
 * picture and the position. A sheet where the envelope never arrived draws a flat
 * row of stubs and still looks like a perfectly reasonable player, which is
 * exactly how a broken measure would pass review. So the sheets are drawn with a
 * real envelope, without one, and at the ends of the width ladder, and the
 * resting state is photographed on purpose rather than avoided.
 */
class WavePlayerRenderProbe {

    /**
     * A synthetic envelope with a quiet lead-in, a loud body and a fade, so the
     * played and unplayed inks are both visible and the outline is not a
     * featureless block.
     */
    private val sample: Waveform = Waveform(
        FloatArray(512) { i ->
            val t = i / 512f
            val body = abs(sin(t * 18f)) * 0.55f + 0.35f
            when {
                t < 0.04f -> 0.02f
                t > 0.94f -> body * (1f - (t - 0.94f) / 0.06f)
                else -> body
            }
        },
    )

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    private val track = TrackInfo(
        title = "Sacrifice",
        artist = "Taka feat. めらみぽっぷ",
        album = "追憶のサクラメント",
        artwork = null,
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
            File("build/render/player-wave-$name.png").writeBytes(it)
        }
    }

    private @androidx.compose.runtime.Composable fun card(
        state: PlaybackState,
        waveform: Waveform?,
        queueSize: Int = 4,
        showTimes: Boolean = true,
    ) = WavePlayerCard(
        state = state, track = if (state is PlaybackState.Idle) null else track,
        waveform = waveform, volume = 0.7f, repeat = RepeatMode.Off,
        queueSize = queueSize, showTimes = showTimes,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
    )

    /** The card in both palettes, measured and not yet measured. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("card-${if (dark) "dark" else "light"}", 400, 430, dark) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    // Measured, then the resting state, then a track whose envelope
                    // is all silence. The second and the third must not look alike:
                    // one is waiting and one is an answer.
                    Box(Modifier.width(340.dp)) { card(playing(107_000L), sample) }
                    Box(Modifier.width(340.dp)) { card(playing(107_000L), null) }
                    Box(Modifier.width(340.dp)) { card(playing(107_000L), Waveform(FloatArray(512))) }
                }
            }
        }
    }

    /** Empty, and a file that would not open. */
    @Test
    fun states() {
        sheet("states", 400, 240, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
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

    /**
     * The width sweep. The concept was drawn once at 340dp and a slot is not
     * obliged to give it that. The two thresholds are the times at 260 and the
     * skips at 300, so the ladder has to read as one element leaving at a time.
     */
    @Test
    fun widths() {
        val widths = listOf(400, 340, 300, 280, 260, 240, 200, 160)
        sheet("widths", 440, 1000, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                widths.forEach { w ->
                    Box(Modifier.width(w.dp)) { card(playing(107_000L), sample) }
                }
            }
        }
    }
}
