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
 * Concept A on screen. Its whole identity is that the card IS the measure, so the
 * sheets are about the boundary: where the fill stops has to be readable at every
 * position, and the text has to survive sitting across it.
 *
 * The positions below are chosen for that rather than for looking pleasant. Zero
 * and one are the ends, where a fill that is drawn one pixel off reads as a card
 * that never empties or never completes, and the middle is where the boundary
 * lands under the title.
 */
class TimelinePlayerRenderProbe {

    private fun at(fraction: Float) = PlaybackState.Playing(
        file = Paths.get("/music/01 - Higurashi no Naku Koro ni.mp3"),
        positionMs = (295_000L * fraction).toLong(),
        durationMs = 295_000L,
    )

    private val sample get() = TrackInfo("Higurashi no Naku Koro ni", "Shimamiya Eiko", null, null)

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
            File("build/render/player-timeline-$name.png").writeBytes(it)
        }
    }

    @Composable
    private fun card(
        state: PlaybackState,
        track: TrackInfo?,
        fill: Float = 0.22f,
        queueSize: Int = 4,
        width: Int = 340,
    ) {
        Box(Modifier.width(width.dp)) {
            TimelinePlayerCard(
                state = state, track = track, volume = 0.7f, repeat = RepeatMode.Off,
                queueSize = queueSize, fill = fill,
                onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
            )
        }
    }

    /** The boundary at every position, on both palettes. */
    @Test
    fun positions() {
        for (dark in listOf(true, false)) {
            sheet("positions-${if (dark) "dark" else "light"}", 380, 560, dark) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                    listOf(0f, 0.08f, 0.36f, 0.72f, 1f).forEach { card(at(it), sample) }
                    // Nothing loaded: no fill at all, and the plane is the picker.
                    card(PlaybackState.Idle, null, queueSize = 0)
                }
            }
        }
    }

    /** The ink at the ends of its range: too faint to read, and loud enough to fight the text. */
    @Test
    fun ink() {
        sheet("ink", 380, 320, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                listOf(0.05f, 0.22f, 0.5f).forEach { card(at(0.45f), sample, fill = it) }
            }
        }
    }

    /** The ladder: the length goes, then the elapsed, then the skips. */
    @Test
    fun widths() {
        sheet("widths", 420, 700, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                listOf(400, 340, 300, 260, 220, 180).forEach { card(at(0.45f), sample, width = it) }
            }
        }
    }
}
