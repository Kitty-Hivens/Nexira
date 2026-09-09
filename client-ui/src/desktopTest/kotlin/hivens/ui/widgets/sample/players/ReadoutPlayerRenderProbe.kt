package hivens.ui.widgets.sample.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Concept C on screen: the shape whose whole point is that it never shows a
 * picture, so the sheets that matter are the ones other players would apologise
 * for -- no tags, no cover, nothing loaded at all.
 *
 * The clock is the element the card is built around, which makes its WIDTH the
 * thing to look at: the sweep below crosses the minute rollover and the
 * ten-minute rollover, because a proportional clock changes width on both and the
 * hero would shuffle sideways five times a second.
 */
class ReadoutPlayerRenderProbe {

    private fun playing(pos: Long, dur: Long = 295_000L) = PlaybackState.Playing(
        file = Paths.get("/music/01 - Higurashi no Naku Koro ni.mp3"), positionMs = pos, durationMs = dur,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, dark: Boolean, body: @Composable () -> Unit) {
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
            File("build/render/player-readout-$name.png").writeBytes(it)
        }
    }

    @Composable
    private fun card(
        state: PlaybackState,
        track: TrackInfo?,
        queueSize: Int = 4,
        showTotal: Boolean = true,
        width: Int = 320,
    ) {
        Box(Modifier.width(width.dp)) {
            ReadoutPlayerCard(
                state = state, track = track, volume = 0.7f, repeat = RepeatMode.Off,
                queueSize = queueSize, showTotal = showTotal,
                onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
            )
        }
    }

    /** The states other players have to apologise for, both palettes. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("states-${if (dark) "dark" else "light"}", 360, 460, dark) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    // Tagged, untagged, and nothing loaded. None of the three is a
                    // degraded version of another: they are the same card.
                    card(playing(107_000L), TrackInfo("Higurashi no Naku Koro ni", "Shimamiya Eiko", null, null))
                    card(playing(12_000L), TrackInfo("01 - Higurashi no Naku Koro ni", null, null, null))
                    card(PlaybackState.Idle, null, queueSize = 0)
                }
            }
        }
    }

    /** The rollovers, which is where a proportional clock would shuffle. */
    @Test
    fun clock() {
        sheet("clock", 360, 460, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                card(playing(9_000L), TrackInfo("Nine seconds in", null, null, null))
                card(playing(59_000L), TrackInfo("Just before the minute", null, null, null))
                card(playing(599_000L, 3_600_000L), TrackInfo("Just before ten minutes", null, null, null))
            }
        }
    }

    /** The ladder: the total goes first, then the skips, and the clock never goes. */
    @Test
    fun widths() {
        val widths = listOf(360, 320, 300, 280, 240, 200, 160)
        sheet("widths", 400, 1000, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                widths.forEach { w ->
                    card(
                        playing(107_000L),
                        TrackInfo("Higurashi no Naku Koro ni", "Shimamiya Eiko", null, null),
                        width = w,
                    )
                }
            }
        }
    }
}
