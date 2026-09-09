package hivens.ui.widgets.sample.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
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
 * A readout, never a picture.
 *
 * Every other player kind leads with the artwork and then has to answer for the
 * files that carry none: a note glyph in a grey square, which is a picture
 * apologising for not being one. This shape stops pretending. The hero is the
 * time, the way a departure board's hero is the time, and the title is demoted to
 * a caption above it. Nothing here can look empty, because nothing here was ever
 * going to be a picture.
 *
 * That makes it the kind for a corpus of loose files -- a folder of tracks ripped
 * out of a game, say, where tags are patchy and embedded covers are the exception.
 *
 * The hero is set in the mono family, which the concept sheet did not have to
 * think about because it was drawn once: a proportional clock re-measures on every
 * tick, so the digits shuffle sideways five times a second and the one element the
 * card is built around is the one that will not hold still.
 */
@Serializable
data class ReadoutPlayerProps(
    /**
     * Whether the total sits beside the elapsed. Off is the pure departure-board
     * reading: how far in, with no answer to how much is left.
     */
    @PropLabel("widget.home.new.player.readout.showTotal") val showTotal: Boolean = true,
)

@Widget(
    id = "home.new.player.readout",
    displayName = "widget.home.new.player.readout",
    propsClass = ReadoutPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun ReadoutPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ReadoutPlayerProps>()
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

    ReadoutPlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showTotal   = p.showTotal,
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
internal fun ReadoutPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showTotal: Boolean,
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
            // One threshold per element, and they do not consult each other. Making
            // the total's depend on whether the skips were drawn was rational --
            // the room they gave up is room the total can have -- and it read as a
            // fault: the length vanished at 280 and came BACK at 240, so an element
            // blinked out and returned as the card got narrower.
            //
            // Order of sacrifice, widest first: the length, then the skips. Actions
            // outrank a readout, which is the same call the cover player makes. The
            // elapsed never goes at all, because it IS the card.
            val total = showTotal && maxWidth >= 300.dp
            val skips = queueSize > 1 && maxWidth >= 240.dp
            Box(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // With nothing loaded the whole plane opens the picker. There
                        // is no cover square here to click, and a player that says
                        // "pick a track" and answers nowhere is a dead end.
                        .then(if (idle) Modifier.clickable(onClick = onPick) else Modifier)
                        .padding(14.dp),
                ) {
                    val name = playerTitle(state, track, s)
                    Text(
                        text       = name,
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = palette.textSecondary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(name),
                        // Room for the overflow, which floats over this corner.
                        modifier   = Modifier.padding(end = 28.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text       = elapsedLabel(state).ifEmpty { EMPTY_CLOCK },
                            style      = MaterialTheme.typography.headlineMedium,
                            color      = palette.textPrimary,
                            fontWeight = FontWeight.Medium,
                            fontFamily = LocalMonoFamily.current,
                            maxLines   = 1,
                            softWrap   = false,
                        )
                        if (total) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text       = "/ ${totalLabel(state).ifEmpty { EMPTY_CLOCK }}",
                                style      = MaterialTheme.typography.bodyMedium,
                                color      = palette.textSecondary,
                                fontFamily = LocalMonoFamily.current,
                                maxLines   = 1,
                                softWrap   = false,
                                // Sits on the hero's baseline rather than its box.
                                modifier   = Modifier.padding(bottom = 3.dp),
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
                                iconSize           = 18.dp,
                                fill               = 1f,
                                weight             = 500,
                            )
                        }
                        NxIconButton(
                            icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                            contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                            onClick            = onPlayPause,
                            tint               = palette.textPrimary,
                            enabled            = loaded,
                            iconSize           = 22.dp,
                            fill               = 1f,
                            weight             = 500,
                        )
                        if (skips) {
                            NxIconButton(
                                icon               = NxIcon.SkipNext,
                                contentDescription = s.audioSkipNext,
                                onClick            = onSkipNext,
                                tint               = palette.textPrimary.copy(alpha = 0.7f),
                                enabled            = loaded,
                                iconSize           = 18.dp,
                                fill               = 1f,
                                weight             = 500,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // A hairline, not a scrubber: this card already prints the
                    // position in figures, so the bar is the one thing it does not
                    // need to be read from. It stays seekable because a measure you
                    // can see and cannot move is a worse deal than one you can.
                    PlaybackScrubber(
                        fraction       = progressFraction(state),
                        enabled        = loaded && duration > 0L,
                        onSeekFraction = { onSeek((it * duration).toLong()) },
                        modifier       = Modifier.fillMaxWidth(),
                    )
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

/**
 * What the clock reads with nothing loaded.
 *
 * Dashes rather than 0:00, which would be a position in a track that is not there,
 * and rather than an empty string, which would collapse the one element the card is
 * built around and change its height the moment a file arrives.
 */
private const val EMPTY_CLOCK = "--:--"
