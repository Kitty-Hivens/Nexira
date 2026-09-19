package hivens.ui.widgets.players

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.audio.Waveform
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCycleToggle
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxPanelGroup
import hivens.ui.nx.NxPopoverPanel
import hivens.ui.nx.NxSlider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.widgets.services.MusicPlayerService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The cover is the ground, the sound is drawn on it.
 *
 * The card is made of the two things the file itself carries and of nothing
 * else: its artwork, blurred until it is a colour field rather than a picture,
 * and its envelope laid across the bottom. There is no surface under it, no
 * plate behind the text, no bar beside the wave. What the widget looks like is
 * what is playing.
 *
 * Blurred rather than shown sharp, which is the whole difference from the
 * cover-led kind. A sharp cover wants to be looked at and competes with the
 * words on top of it; the same picture out of focus keeps its colour and its
 * light and gives the text somewhere to sit. The concept sheet faked this with
 * a downsample because the off-screen renderer has no blur, and here it is the
 * real thing.
 *
 * With no artwork the ground is generated from the palette instead. Not a note
 * glyph on grey: this shape is a field of colour by construction, so the empty
 * case is a different field and never an apology for a missing picture.
 */
@Serializable
data class GroundPlayerProps(
    /**
     * How far the ground is darkened under the text. The one knob worth exposing,
     * because it is the trade the design makes: darker is easier to read and less
     * of the cover, and which side of that is right depends on the artwork and on
     * the wallpaper behind the launcher.
     */
    @PropLabel("widget.home.new.player.ground.dim") val dim: Float = 0.6f,
)

@Widget(
    id = "home.new.player.ground",
    displayName = "widget.home.new.player.ground",
    propsClass = GroundPlayerProps::class,
    drawsOwnSurface = true,
)
@Composable
fun GroundPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<GroundPlayerProps>()
    val player: MusicPlayerService = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val repeat by player.repeat.collectAsState()
    val queue by player.queue.collectAsState()
    val track by player.track.collectAsState()
    val scope = rememberCoroutineScope()

    val openTracks = rememberAudioFilesPicker(scope) { player.open(it) }
    val waveform = rememberWaveform(state.file)

    GroundPlayerCard(
        state       = state,
        track       = track,
        waveform    = waveform,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        dim         = p.dim,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
        onRepeat    = { player.setRepeat(it) },
        onSkipNext  = { player.skipToNext() },
        onSkipPrev  = { player.skipToPrevious() },
        onSeek      = { player.seek(it) },
    )
}

/** The card over plain data, so it renders off-screen across palettes. */
@Composable
internal fun GroundPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    waveform: Waveform?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    dim: Float,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val palette = NxTheme.colors
    val idle = state is PlaybackState.Idle
    val loaded = !idle && state !is PlaybackState.Error
    val duration = durationMsOf(state)
    var menuOpen by remember { mutableStateOf(false) }
    val clamped = dim.coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            // With nothing loaded the whole plane opens the picker: the ground is
            // the only affordance this shape has, so it has to be the one.
            .openWhenEmpty(idle, s.audioPickTrack, onPick),
    ) {
        Ground(track?.artwork, palette.surfaceContainer, palette.primary, palette.tertiary)
        // Three stops, and only the middle one is on the knob.
        //
        // The ends carry the text and the wave, so their cover is not a matter of
        // taste: a probe drawn over a bright cover with the darkening at zero put
        // white letters on yellow and an unplayed wave that vanished into the
        // picture. What the knob moves is the middle, where nothing sits and the
        // cover is free to show through, so the control does something plainly
        // visible and still cannot produce a card nobody can read.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = TEXT_COVER),
                    0.5f to Color.Black.copy(alpha = lerpAlpha(MIDDLE_OPEN, MIDDLE_CLOSED, clamped)),
                    1f to Color.Black.copy(alpha = WAVE_COVER),
                ),
            ),
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // One threshold, and only one element can leave. The wave is the
            // measure and the transport is the point, so what goes is the pair of
            // skips, and below that nothing else may.
            val skips = queueSize > 1 && maxWidth >= 280.dp
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 24.dp)) {
                        val name = playerTitle(state, track, s)
                        Text(
                            text       = name,
                            style      = MaterialTheme.typography.titleMedium,
                            color      = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(name),
                        )
                        val under = track?.artist ?: playerStatus(state, s)
                        Text(
                            text       = under,
                            style      = MaterialTheme.typography.bodySmall,
                            color      = Color.White.copy(alpha = 0.75f),
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(under),
                        )
                    }
                    if (skips) {
                        NxIconButton(
                            icon               = NxIcon.SkipPrevious,
                            contentDescription = s.audioSkipPrevious,
                            onClick            = onSkipPrev,
                            tint               = Color.White.copy(alpha = 0.8f),
                            enabled            = loaded,
                            iconSize           = 18.dp,
                            fill               = 1f,
                            weight             = 500,
                        )
                    }
                    NxIconButton(
                        icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                        contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                        onClick            = onPlayPause,
                        tint               = Color.White,
                        enabled            = loaded,
                        iconSize           = 24.dp,
                        fill               = 1f,
                        weight             = 500,
                    )
                    if (skips) {
                        NxIconButton(
                            icon               = NxIcon.SkipNext,
                            contentDescription = s.audioSkipNext,
                            onClick            = onSkipNext,
                            tint               = Color.White.copy(alpha = 0.8f),
                            enabled            = loaded,
                            iconSize           = 18.dp,
                            fill               = 1f,
                            weight             = 500,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                WaveformStrip(
                    waveform       = waveform,
                    fraction       = progressFraction(state),
                    played         = palette.primary,
                    // White rather than the tonal secondary: the ink under this
                    // strip is a photograph, and a palette grey disappears into it
                    // on half the covers there are.
                    remaining      = Color.White.copy(alpha = 0.34f),
                    onSeekFraction = if (loaded && duration > 0L) {
                        { onSeek((it * duration).toLong()) }
                    } else {
                        null
                    },
                    modifier       = Modifier.fillMaxWidth().height(26.dp),
                )
            }

            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                NxIconButton(
                    icon               = NxIcon.MoreVert,
                    contentDescription = s.packCardMore,
                    onClick            = { menuOpen = true },
                    tint               = Color.White.copy(alpha = 0.8f),
                )
                NxPopoverPanel(
                    expanded         = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    title            = s.audioPlaybackOptions,
                    footer           = {
                        NxButton(
                            label   = s.audioOpenFile,
                            onClick = { menuOpen = false; onPick() },
                            style   = NxButtonStyle.Tertiary,
                            icon    = NxIcon.FolderOpen,
                            compact = true,
                        )
                        NxButton(
                            label   = s.audioStop,
                            onClick = { menuOpen = false; onStop() },
                            style   = NxButtonStyle.Tertiary,
                            icon    = NxIcon.Stop,
                            enabled = loaded,
                            compact = true,
                        )
                    },
                ) {
                    NxSlider(
                        label         = s.audioVolume,
                        value         = volume,
                        range         = 0f..1f,
                        valueText     = "${(volume * 100).toInt()}%",
                        onValueChange = onVolume,
                        compact       = true,
                    )
                    NxPanelGroup(label = s.audioRepeat) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NxCycleToggle(
                                states  = repeatStates(s),
                                index   = repeatIndex(repeat),
                                onCycle = { onRepeat(REPEAT_ORDER[it]) },
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text  = repeatAnswer(repeat, s),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The colour field the card is built on.
 *
 * The artwork out of focus where there is one, and a two-stop gradient off the
 * palette where there is not. The blur is unbounded because the card clips it:
 * the alternative samples the picture's own edge and leaves a bright rim where
 * the cover meets the corner.
 */
@Composable
private fun Ground(artwork: ImageBitmap?, base: Color, primary: Color, tertiary: Color) {
    if (artwork != null) {
        Image(
            bitmap             = artwork,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize().blur(GROUND_BLUR, BlurredEdgeTreatment.Unbounded),
        )
    } else {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(lerp(base, primary, 0.30f), lerp(base, tertiary, 0.16f)),
                ),
            ),
        )
    }
}

/**
 * Fixed rather than wrapped, because this shape is a proportion and not a stack
 * of rows: the ground is a picture, and a card that grows with its text turns the
 * picture into a band.
 */
private val CARD_HEIGHT = 112.dp

/**
 * Far enough that the cover stops being a picture and becomes light. Short of
 * that it reads as a photograph nobody can see properly, which is worse than
 * either end.
 */
private val GROUND_BLUR = 28.dp

/** What the title band always gets, whatever the knob says. */
private const val TEXT_COVER = 0.62f

/** The same for the strip, which is a shade heavier: it is thin and white. */
private const val WAVE_COVER = 0.70f

private const val MIDDLE_OPEN = 0.08f

private const val MIDDLE_CLOSED = 0.68f

private fun lerpAlpha(from: Float, to: Float, t: Float): Float = from + (to - from) * t
