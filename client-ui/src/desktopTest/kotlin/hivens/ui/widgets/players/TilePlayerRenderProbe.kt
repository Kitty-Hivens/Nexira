package hivens.ui.widgets.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import hivens.ui.settle

/**
 * Concepts B and D on screen, in one probe because they share the question.
 *
 * Both hide their controls until the pointer arrives, and that resting state is
 * the design: a wall of tiles is meant to read as artwork, and a token is meant
 * to read as a dot that happens to know the time. Neither state can be produced
 * by a probe that only draws the default, so both are drawn at rest and revealed,
 * side by side, which is the only way to see whether the difference is worth the
 * hiding.
 *
 * The chrome frame is drawn with the animation settled, since a single render
 * catches an animateFloatAsState at its starting value: what is passed is the
 * end state rather than a hover, and the fade itself is not what these sheets
 * are for.
 */
class TilePlayerRenderProbe {

    private val cover by lazy { ProbeSample.cover() }

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    private val track get() = TrackInfo(
        title = "追憶のサクラメント",
        artist = "Taka feat. めらみぽっぷ",
        album = null,
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
        // The chrome fades in, and one render photographs the fade at zero. A few
        // frames of clock settle it without the sheet being about the animation.
        val t = scene.settle(frames = 30)
        val img = scene.render(t)
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-tile-$name.png").writeBytes(it)
        }
    }

    private @androidx.compose.runtime.Composable fun tile(
        info: TrackInfo?,
        chrome: Boolean,
        showCaption: Boolean = true,
        state: PlaybackState = playing(107_000L),
    ) = TilePlayerCard(
        state = state, track = info, volume = 0.7f, repeat = RepeatMode.Off,
        queueSize = 4, showCaption = showCaption, chrome = chrome,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {},
    )

    private @androidx.compose.runtime.Composable fun token(
        info: TrackInfo?,
        chrome: Boolean,
        showCover: Boolean = false,
        state: PlaybackState = playing(107_000L),
    ) = TokenPlayerCard(
        state = state, track = info, volume = 0.7f, repeat = RepeatMode.Off,
        queueSize = 4, showCover = showCover, chrome = chrome,
        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
        onRepeat = {}, onSkipNext = {}, onSkipPrev = {},
    )

    /** The tile at rest and revealed, with a cover and without, in both palettes. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("b-${if (dark) "dark" else "light"}", 900, 260, dark) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    Box(Modifier.width(196.dp)) { tile(track, chrome = false) }
                    Box(Modifier.width(196.dp)) { tile(track, chrome = true) }
                    Box(Modifier.width(196.dp)) {
                        tile(TrackInfo("audio", null, null, null), chrome = false)
                    }
                    Box(Modifier.width(196.dp)) { tile(track, chrome = false, showCaption = false) }
                }
            }
        }
    }

    /** The token at rest and revealed, as a glyph and as a cover. */
    @Test
    fun tokens() {
        for (dark in listOf(true, false)) {
            sheet("d-${if (dark) "dark" else "light"}", 520, 160, dark) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    Box(Modifier.width(96.dp)) { token(track, chrome = false) }
                    Box(Modifier.width(96.dp)) { token(track, chrome = true) }
                    Box(Modifier.width(96.dp)) { token(track, chrome = false, showCover = true) }
                    Box(Modifier.width(96.dp)) { token(track, chrome = true, showCover = true) }
                    Box(Modifier.width(96.dp)) { token(null, chrome = false, state = PlaybackState.Idle) }
                }
            }
        }
    }

    /**
     * A slot far wider than the object, which is the shape of the defect these
     * four had: `fillMaxWidth` plus a square aspect handed a home-screen slot made
     * a token seventeen hundred points across. What is drawn here is one tile and
     * one token in a sheet several times their own width, so the sheet is mostly
     * empty if the cap holds and mostly object if it does not.
     */
    @Test
    fun capped() {
        sheet("capped", 700, 320, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                tile(track, chrome = false)
                token(track, chrome = false)
            }
        }
    }

    /** Both at the ends of their size range, where the thresholds live. */
    @Test
    fun widths() {
        // Split across rows on purpose. A single row of every size sums past the
        // sheet, and an overflowing Row does not clip its children: one tile took
        // the whole page and the sweep read as a widget defect rather than as a
        // probe that asked for more room than it had.
        sheet("sizes", 700, 660, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s10), verticalAlignment = Alignment.Top) {
                    listOf(240, 196, 160).forEach { w ->
                        Box(Modifier.width(w.dp)) { tile(track, chrome = false) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s10), verticalAlignment = Alignment.Top) {
                    listOf(132, 112, 96, 80).forEach { w ->
                        Box(Modifier.width(w.dp)) { tile(track, chrome = false) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s10), verticalAlignment = Alignment.Top) {
                    listOf(140, 112, 96, 84, 72, 56).forEach { w ->
                        Box(Modifier.width(w.dp)) { token(track, chrome = true) }
                    }
                }
            }
        }
    }
}
