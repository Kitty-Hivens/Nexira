package hivens.ui.widgets.sample.players

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
 * Concept G on screen.
 *
 * The risk in this shape is crowding. Every element is inside one square, the
 * ring's bar count comes from the circumference, and the transport sits on top of
 * the label, so a size that works at one diameter can turn into a solid disc at a
 * smaller one or into a sparse fan at a larger one. The sweep is therefore the
 * important sheet here rather than an afterthought, and it goes down to a cell
 * small enough to be unreasonable.
 */
class RecordPlayerRenderProbe {

    private val cover by lazy { ProbeSample.cover() }

    private val sample: Waveform = Waveform(
        FloatArray(512) { i ->
            val t = i / 512f
            abs(sin(t * 26f)) * 0.55f + 0.3f
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
                        contentAlignment = Alignment.TopStart,
                    ) { body() }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-record-$name.png").writeBytes(it)
        }
    }

    private @androidx.compose.runtime.Composable fun card(
        state: PlaybackState,
        info: TrackInfo?,
        waveform: Waveform? = sample,
        showCaption: Boolean = false,
    ) = RecordPlayerCard(
        state = state, track = info, waveform = waveform, volume = 0.7f,
        repeat = RepeatMode.Off, queueSize = 4, showCaption = showCaption,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {},
    )

    /** Both palettes, with a cover and without, and with the caption turned on. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("card-${if (dark) "dark" else "light"}", 560, 220, dark) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    Box(Modifier.width(168.dp)) { card(playing(107_000L), track) }
                    Box(Modifier.width(168.dp)) {
                        card(playing(107_000L), TrackInfo("audio", null, null, null))
                    }
                    Box(Modifier.width(168.dp)) { card(playing(107_000L), track, showCaption = true) }
                }
            }
        }
    }

    /** Nothing loaded, and a track not yet measured, which must not look the same. */
    @Test
    fun states() {
        sheet("states", 380, 220, dark = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Box(Modifier.width(168.dp)) { card(PlaybackState.Idle, null, waveform = null) }
                Box(Modifier.width(168.dp)) { card(playing(107_000L), track, waveform = null) }
            }
        }
    }

    /**
     * The size sweep. The bar count is taken from the circumference, so this is
     * where crowding at the small end and thinning at the large end show up.
     */
    @Test
    fun widths() {
        val sizes = listOf(240, 196, 168, 140, 112, 88, 64)
        sheet("widths", 1100, 300, dark = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s10), verticalAlignment = Alignment.Top) {
                sizes.forEach { w ->
                    Box(Modifier.width(w.dp)) { card(playing(107_000L), track) }
                }
            }
        }
    }
}
