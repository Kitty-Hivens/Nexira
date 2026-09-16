package hivens.ui.widgets.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 * Concept I on screen.
 *
 * This one is about fitting a space the other kinds do not: a rail is narrow and
 * the words in it wrap, so the sheet has to be drawn with a title that actually
 * wraps rather than with a short one that flatters the layout. The long Japanese
 * credit is here for that reason and not for decoration.
 *
 * The strip is fixed in height while the words above it are not, which is the
 * pairing most likely to go wrong: a two-line title and a two-line artist have to
 * leave the clock room, and the measure must not change scale from track to
 * track.
 */
class ColumnPlayerRenderProbe {

    private val cover by lazy { ProbeSample.cover() }

    private val sample: Waveform = Waveform(
        FloatArray(512) { i ->
            val t = i / 512f
            abs(sin(t * 20f)) * 0.6f + 0.25f
        },
    )

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    private val long get() = TrackInfo(
        title = "追憶のサクラメント",
        artist = "Taka feat. めらみぽっぷ",
        album = null,
        artwork = cover,
    )

    private val short get() = TrackInfo("Bus Stop", "FinBosh", null, cover)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, dark: Boolean, body: @androidx.compose.runtime.Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((wDp * d).toInt(), (hDp * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.ENGLISH) {
                NxTheme(useDarkTheme = dark) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                        contentAlignment = Alignment.TopStart,
                    ) { body() }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-column-$name.png").writeBytes(it)
        }
    }

    private @androidx.compose.runtime.Composable fun card(
        state: PlaybackState,
        info: TrackInfo?,
        waveform: Waveform? = sample,
        showCover: Boolean = true,
        queueSize: Int = 4,
    ) = ColumnPlayerCard(
        state = state, track = info, waveform = waveform, volume = 0.7f,
        repeat = RepeatMode.Off, queueSize = queueSize, showCover = showCover,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {},
    )

    /** A wrapping title, a short one, and the same without the artwork. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("card-${if (dark) "dark" else "light"}", 560, 400, dark) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(148.dp)) { card(playing(107_000L), long) }
                    Box(Modifier.width(148.dp)) { card(playing(107_000L), short) }
                    Box(Modifier.width(148.dp)) { card(playing(107_000L), long, showCover = false) }
                }
            }
        }
    }

    /** No artwork, nothing loaded, and a track not yet measured. */
    @Test
    fun states() {
        sheet("states", 560, 400, dark = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(148.dp)) {
                    card(playing(12_000L), TrackInfo("audio", null, null, null))
                }
                Box(Modifier.width(148.dp)) { card(PlaybackState.Idle, null, waveform = null, queueSize = 0) }
                Box(Modifier.width(148.dp)) { card(playing(107_000L), long, waveform = null) }
            }
        }
    }

    /** The rail is not one width. */
    @Test
    fun widths() {
        val widths = listOf(200, 172, 148, 128, 112, 96)
        sheet("widths", 940, 420, dark = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s10), verticalAlignment = Alignment.Top) {
                widths.forEach { w ->
                    Box(Modifier.width(w.dp)) { card(playing(107_000L), long) }
                }
            }
        }
    }
}
