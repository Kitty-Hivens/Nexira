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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
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

/** Renders the cover player over fixed data so its layout can be looked at. */
class CoverPlayerRenderProbe {

    private val cover by lazy { ProbeSample.cover() }

    private fun playing(pos: Long) = PlaybackState.Playing(
        file = Paths.get("/music/audio.mp3"), positionMs = pos, durationMs = 295_000L,
    )

    @Composable
    private fun row(state: PlaybackState, track: TrackInfo?, showAlbum: Boolean) {
        Box(Modifier.width(420.dp)) {
            CoverPlayerCard(
                state = state, track = track, volume = 0.7f, repeat = RepeatMode.Off,
                queueSize = 1, showAlbum = showAlbum,
                onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {}, onRepeat = {},
                onSkipNext = {}, onSkipPrev = {}, onSeek = {},
            )
        }
    }

    /**
     * A sweep of widths, because the card's width is not the designer's choice:
     * a free-canvas placement goes to 48dp and a cube-grid cell is the slot over
     * the column count. Drawing one comfortable width is what let #662 ship.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun widths() {
        val widths = listOf(460, 380, 320, 280, 240, 200, 160)
        val d = 2f
        val scene = ImageComposeScene((500 * d).toInt(), (1240 * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.ENGLISH) {
                NxTheme(useDarkTheme = true) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s10)) {
                            widths.forEach { w ->
                                Box(Modifier.width(w.dp)) {
                                    CoverPlayerCard(
                                        state = playing(107_000L),
                                        track = TrackInfo(
                                            title = "Sacrifice",
                                            artist = "Taka feat. めらみぽっぷ",
                                            album = "追憶のサクラメント",
                                            artwork = cover,
                                        ),
                                        volume = 0.7f, repeat = RepeatMode.Off,
                                        queueSize = 4, showAlbum = true,
                                        onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                                        onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-cover-widths.png").writeBytes(it)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun probe() {
        val d = 2f
        val scene = ImageComposeScene((460 * d).toInt(), (520 * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.ENGLISH) {
                NxTheme(useDarkTheme = true) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s16)) {
                            row(
                                playing(107_000L),
                                TrackInfo(
                                    title = "Sacrifice",
                                    artist = "Taka feat. めらみぽっぷ",
                                    album = "追憶のサクラメント",
                                    artwork = cover,
                                ),
                                showAlbum = true,
                            )
                            row(
                                playing(12_000L),
                                TrackInfo(title = "audio", artist = null, album = null, artwork = null),
                                showAlbum = true,
                            )
                            row(PlaybackState.Idle, null, showAlbum = true)
                        }
                    }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/player-cover.png").writeBytes(it)
        }
    }

    /**
     * The overflow with the panel open, driven by a real press on the button.
     *
     * The volume slider inside a context menu overflowed it, which is what moved
     * this to a panel; a sheet that renders the closed card says nothing about
     * whether the replacement fits either. Two palettes and the loop in a state
     * that is NOT off, so the lit control is on screen rather than assumed.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun panel() {
        for (dark in listOf(true, false)) {
            val d = 2f
            val scene = ImageComposeScene((460 * d).toInt(), (420 * d).toInt(), density = Density(d)) {
                LocaleProvider(AppLocale.RUSSIAN) {
                    NxTheme(useDarkTheme = dark) {
                        Box(
                            Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Box(Modifier.width(420.dp)) {
                                CoverPlayerCard(
                                    state = playing(107_000L),
                                    track = TrackInfo(
                                        title = "Sacrifice",
                                        artist = "Taka feat. \u3081\u3089\u307f\u307d\u3063\u3077",
                                        album = "\u8ffd\u61b6\u306e\u30b5\u30af\u30e9\u30e1\u30f3\u30c8",
                                        artwork = cover,
                                    ),
                                    volume = 0.62f, repeat = RepeatMode.One,
                                    queueSize = 4, showAlbum = true,
                                    onPick = {}, onPlayPause = {}, onStop = {}, onVolume = {},
                                    onRepeat = {}, onSkipNext = {}, onSkipPrev = {}, onSeek = {},
                                )
                            }
                        }
                    }
                }
            }
            var t = 0L
            scene.render(t)
            // The overflow sits at the card's top-right corner, inset by its own padding.
            val at = Offset(414f, 38f) * d
            scene.sendPointerEvent(PointerEventType.Enter, at)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            var img = scene.render(t)
            repeat(45) {
                t += 16_000_000L
                img = scene.render(t)
            }
            scene.close()
            File("build/render").mkdirs()
            img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
                File("build/render/player-cover-panel-${if (dark) "dark" else "light"}.png").writeBytes(it)
            }
        }
    }
}
