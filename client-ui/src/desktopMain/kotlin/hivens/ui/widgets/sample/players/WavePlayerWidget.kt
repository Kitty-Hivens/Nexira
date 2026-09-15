package hivens.ui.widgets.sample.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
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
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.widgets.sample.durationMsOf
import hivens.ui.widgets.sample.elapsedLabel
import hivens.ui.widgets.sample.progressFraction
import hivens.ui.widgets.sample.totalLabel
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The sound is the picture.
 *
 * Every other kind that leads with an image has to answer for the files that
 * carry none, and the readout answers by refusing pictures altogether. This one
 * answers differently: it draws the one image every audio file can produce, its
 * own envelope. A track that has no cover still has a shape, and that shape is
 * more this track than a note glyph in a grey square will ever be.
 *
 * The envelope is the measure at the same time, which is why there is no bar
 * under it. The played bars are inked and the rest are not, so position is read
 * off the picture instead of off a second element repeating what the picture
 * already says, and dragging across it seeks.
 *
 * Measuring a track means decoding it, about a second in the background, once
 * per file. Until that lands the strip rests flat at its floor height rather
 * than collapsing, so the card is the same height throughout and a file that
 * cannot be measured degrades to a plain row instead of a hole.
 *
 * The concept sheet drew one large transport and nothing else, and the left of
 * this card keeps that exactly. Skipping arrives on the right of the times row,
 * where the sheet had room to spare, rather than beside the play control, so a
 * queue is reachable without the hero becoming a row of three.
 */
@Serializable
data class WavePlayerProps(
    /**
     * Whether the elapsed and the total sit under the envelope. Off is the purest
     * reading of the concept: the picture is the position, in figures nowhere.
     */
    @PropLabel("widget.home.new.player.wave.showTimes") val showTimes: Boolean = true,
)

@Widget(
    id = "home.new.player.wave",
    displayName = "widget.home.new.player.wave",
    propsClass = WavePlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun WavePlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<WavePlayerProps>()
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val repeat by player.repeat.collectAsState()
    val queue by player.queue.collectAsState()
    val track by player.track.collectAsState()
    val scope = rememberCoroutineScope()

    val musicService = remember(player) { MusicPlayerServiceImpl(player) }
    provideService(MusicPlayerService::class, instance.instanceId, musicService)

    val openTracks = rememberAudioFilesPicker(scope) { player.open(it) }
    val waveform = rememberWaveform(state.file)

    WavePlayerCard(
        state       = state,
        track       = track,
        waveform    = waveform,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showTimes   = p.showTimes,
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
internal fun WavePlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    waveform: Waveform?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showTimes: Boolean,
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

    NxSurface(
        level    = NxSurfaceLevel.Floating,
        modifier = modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One threshold per element, and they do not consult each other, which
            // is the rule the readout card learned the hard way: a threshold that
            // depends on another element makes something vanish at one width and
            // come back at a narrower one.
            //
            // Order of sacrifice, widest first: the times, then the skips. The
            // envelope never goes, because it IS the card, and neither does the
            // transport.
            val times = showTimes && maxWidth >= 260.dp
            val skips = queueSize > 1 && maxWidth >= 300.dp
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // With nothing loaded the plane opens the picker: there is no
                        // artwork here to click, and an empty player that answers
                        // nowhere is a dead end.
                        .then(if (idle) Modifier.clickable(onClick = onPick) else Modifier)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(palette.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        NxIconButton(
                            icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                            contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                            onClick            = onPlayPause,
                            tint               = palette.onPrimaryContainer,
                            enabled            = loaded,
                            iconSize           = 22.dp,
                            fill               = 1f,
                            weight             = 500,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val name = playerTitle(state, track, s)
                        Text(
                            text       = name,
                            style      = MaterialTheme.typography.bodyLarge,
                            color      = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(name),
                            // Room for the overflow, which floats over this corner.
                            modifier   = Modifier.padding(end = 28.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        WaveformStrip(
                            waveform       = waveform,
                            fraction       = progressFraction(state),
                            played         = palette.primary,
                            remaining      = palette.textSecondary.copy(alpha = 0.28f),
                            onSeekFraction = if (loaded && duration > 0L) {
                                { onSeek((it * duration).toLong()) }
                            } else {
                                null
                            },
                            modifier       = Modifier.fillMaxWidth().height(34.dp),
                        )
                        if (times || skips) {
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (times) {
                                    Text(
                                        text       = elapsedLabel(state).ifEmpty { EMPTY_CLOCK },
                                        style      = MaterialTheme.typography.labelSmall,
                                        color      = palette.textSecondary,
                                        fontFamily = LocalMonoFamily.current,
                                        maxLines   = 1,
                                        softWrap   = false,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (skips) {
                                    NxIconButton(
                                        icon               = NxIcon.SkipPrevious,
                                        contentDescription = s.audioSkipPrevious,
                                        onClick            = onSkipPrev,
                                        tint               = palette.textPrimary.copy(alpha = 0.7f),
                                        enabled            = loaded,
                                        iconSize           = 16.dp,
                                        fill               = 1f,
                                        weight             = 500,
                                    )
                                    NxIconButton(
                                        icon               = NxIcon.SkipNext,
                                        contentDescription = s.audioSkipNext,
                                        onClick            = onSkipNext,
                                        tint               = palette.textPrimary.copy(alpha = 0.7f),
                                        enabled            = loaded,
                                        iconSize           = 16.dp,
                                        fill               = 1f,
                                        weight             = 500,
                                    )
                                    if (times) Spacer(Modifier.weight(1f))
                                }
                                if (times) {
                                    Text(
                                        text       = totalLabel(state).ifEmpty { EMPTY_CLOCK },
                                        style      = MaterialTheme.typography.labelSmall,
                                        color      = palette.textSecondary,
                                        fontFamily = LocalMonoFamily.current,
                                        maxLines   = 1,
                                        softWrap   = false,
                                    )
                                }
                            }
                        }
                    }
                }

                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    NxIconButton(
                        icon               = NxIcon.MoreVert,
                        contentDescription = s.packCardMore,
                        onClick            = { menuOpen = true },
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
}

/** What a clock reads with nothing loaded. Dashes rather than a position in a track that is not there. */
private const val EMPTY_CLOCK = "--:--"
