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
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import org.jetbrains.skia.Image as SkImage
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Concept F on screen, which is the only way to see whether it IS concept F.
 *
 * The identity of this shape is one colour: the card's body is the cover's own,
 * lerped into the tonal surface. A sheet where the seed never resolved renders as
 * the plain library surface and looks like a perfectly reasonable player, which is
 * exactly how the first attempt at this widget passed review while being a
 * different design. So the seed is computed off the composition thread and the
 * frame clock is advanced until it lands, and the sheets are captured with a real
 * cover behind them.
 */
class SeededPlayerRenderProbe {

    private val cover by lazy {
        val f = File(
            "SAMPLE_PATH_REMOVED" +
                "SAMPLE_PATH_REMOVED",
        )
        if (f.isFile) SkImage.makeFromEncoded(f.readBytes()).toComposeImageBitmap() else null
    }

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    private val sample get() = TrackInfo(
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
        // The seed lands from another thread, so the clock is advanced until the
        // body colour has had a chance to arrive. One render() would photograph the
        // card before it took the cover's colour.
        var t = 0L
        var img = scene.render(t)
        repeat(40) {
            t += 16_000_000L
            img = scene.render(t)
        }
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-seeded-$name.png").writeBytes(it)
        }
    }

    /** The card itself, both palettes, and the tint at the ends of its range. */
    @Test
    fun probe() {
        for (dark in listOf(true, false)) {
            sheet("card-${if (dark) "dark" else "light"}", 400, 420, dark) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    // The concept's own value, then none, then the far end: if these
                    // three look the same the seed is not reaching the surface.
                    listOf(0.24f, 0f, 0.6f).forEach { tint ->
                        Box(Modifier.width(340.dp)) {
                            SeededPlayerCard(
                                state = playing(107_000L), track = sample, volume = 0.7f,
                                repeat = RepeatMode.Off, queueSize = 4, showAlbum = true, tint = tint,
                                onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                                onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                            )
                        }
                    }
                }
            }
        }
    }

    /** Empty, and with no cover to seed from: the plain body is the fallback. */
    @Test
    fun states() {
        sheet("states", 400, 320, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Box(Modifier.width(340.dp)) {
                    SeededPlayerCard(
                        state = PlaybackState.Idle, track = null, volume = 0.7f,
                        repeat = RepeatMode.Off, queueSize = 0, showAlbum = true, tint = 0.24f,
                        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                        onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                    )
                }
                Box(Modifier.width(340.dp)) {
                    SeededPlayerCard(
                        state = playing(12_000L),
                        track = TrackInfo(title = "audio", artist = null, album = null, artwork = null),
                        volume = 0.7f, repeat = RepeatMode.One, queueSize = 1, showAlbum = true, tint = 0.24f,
                        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                        onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                    )
                }
            }
        }
    }

    /**
     * The width sweep. The concept was drawn once at 340dp; a slot is not obliged
     * to give it that, and the ladder is the only thing standing between it and
     * issue #662.
     */
    @Test
    fun widths() {
        val widths = listOf(400, 340, 300, 280, 240, 200, 160)
        sheet("widths", 440, 1000, dark = true) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                widths.forEach { w ->
                    Box(Modifier.width(w.dp)) {
                        SeededPlayerCard(
                            state = playing(107_000L), track = sample, volume = 0.7f,
                            repeat = RepeatMode.Off, queueSize = 4, showAlbum = true, tint = 0.24f,
                            onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                            onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                        )
                    }
                }
            }
        }
    }
}
