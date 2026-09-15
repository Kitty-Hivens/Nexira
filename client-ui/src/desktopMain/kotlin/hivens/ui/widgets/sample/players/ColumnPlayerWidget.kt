package hivens.ui.widgets.sample.players

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.audio.Waveform
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
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
import hivens.ui.widgets.sample.elapsedLabel
import hivens.ui.widgets.sample.durationMsOf
import hivens.ui.widgets.sample.progressFraction
import hivens.ui.widgets.sample.totalLabel
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The column.
 *
 * Portrait, for the right rail, where every player the launcher had was landscape
 * and none of them fitted. A rail is tall and narrow, so a card built for a home
 * screen either squeezes its row of controls into nothing or spills out of the
 * rail entirely.
 *
 * The envelope runs down the side rather than across, which is the whole reason
 * this shape works in that space: the long axis is the one there is plenty of, so
 * the time goes on it and the width is left for the words. A track's outline read
 * top to bottom is no harder than left to right once nothing else competes for
 * the same direction.
 *
 * There is no drag on the strip here. A vertical gesture inside a rail belongs to
 * the rail, which scrolls, and a widget that steals it makes the rail feel broken
 * to reach past. Seeking is the one thing this kind gives up, and the transport
 * under it is the compensation: full sized, because a rail has the room for it.
 */
@Serializable
data class ColumnPlayerProps(
    /**
     * Whether the artwork sits above the strip. Off for a short rail, where the
     * cover is the first thing worth trading for the words and the envelope, both
     * of which every file has and a cover does not.
     */
    @PropLabel("widget.home.new.player.column.showCover") val showCover: Boolean = true,
    /**
     * How wide the column may be, in points. A rail is narrow by nature and
     * this kind is drawn for one, so a wide slot gets a column rather than a
     * card stretched into the shape of one.
     */
    @PropRange(min = 88.0, max = 320.0)
    @PropLabel("widget.home.new.player.column.size") val size: Int = 148,
)

@Widget(
    id = "home.new.player.column",
    displayName = "widget.home.new.player.column",
    propsClass = ColumnPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun ColumnPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ColumnPlayerProps>()
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

    ColumnPlayerCard(
        state       = state,
        track       = track,
        waveform    = waveform,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showCover   = p.showCover,
        maxSide     = p.size.coerceIn(88, 320).dp,
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
internal fun ColumnPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    waveform: Waveform?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showCover: Boolean,
    maxSide: Dp = 148.dp,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val palette = NxTheme.colors
    val idle = state is PlaybackState.Idle
    val loaded = !idle && state !is PlaybackState.Error
    var menuOpen by remember { mutableStateOf(false) }

    NxSurface(
        level    = NxSurfaceLevel.Floating,
        modifier = modifier.playerObject(maxSide),
        shape    = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One threshold per element, none of them consulting another, which is
            // the rule the readout card paid for. Order of sacrifice, widest first:
            // the total, then the skips, then the strip narrows. The strip itself
            // never goes, because it is the measure, and neither does the title.
            val showTotal = maxWidth >= 140.dp
            val showSkips = queueSize > 1 && maxWidth >= 124.dp
            val stripWidth = if (maxWidth >= 120.dp) 22.dp else 14.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (idle) Modifier.clickable(onClick = onPick) else Modifier)
                    .padding(12.dp),
            ) {
                if (showCover) {
                    Cover(track?.artwork, palette.primary)
                    Spacer(Modifier.height(10.dp))
                }
                Row(Modifier.fillMaxWidth().height(BODY_HEIGHT)) {
                    WaveformColumn(
                        waveform  = waveform,
                        fraction  = progressFraction(state),
                        played    = palette.primary,
                        remaining = palette.textSecondary.copy(alpha = 0.26f),
                        onSeekFraction = if (loaded && durationMsOf(state) > 0L) {
                            { at -> onSeek((at * durationMsOf(state)).toLong()) }
                        } else {
                            null
                        },
                        modifier  = Modifier.width(stripWidth).fillMaxHeight(),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val name = playerTitle(state, track, s)
                        Text(
                            text       = name,
                            style      = MaterialTheme.typography.bodyMedium,
                            color      = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(name),
                            // Room for the overflow, which floats over this corner.
                            modifier   = Modifier.padding(end = 22.dp),
                        )
                        val under = track?.artist ?: playerStatus(state, s)
                        Text(
                            text       = under,
                            style      = MaterialTheme.typography.labelSmall,
                            color      = palette.textSecondary,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(under),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text       = if (showTotal) {
                                "${elapsedLabel(state).ifEmpty { EMPTY_CLOCK }} / " +
                                    totalLabel(state).ifEmpty { EMPTY_CLOCK }
                            } else {
                                elapsedLabel(state).ifEmpty { EMPTY_CLOCK }
                            },
                            style      = MaterialTheme.typography.labelSmall,
                            color      = palette.textSecondary,
                            fontFamily = LocalMonoFamily.current,
                            maxLines   = 1,
                            softWrap   = false,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (showSkips) Arrangement.SpaceBetween else Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSkips) {
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
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(palette.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        NxIconButton(
                            icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                            contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                            onClick            = onPlayPause,
                            tint               = palette.onPrimaryContainer,
                            enabled            = loaded,
                            iconSize           = 20.dp,
                            fill               = 1f,
                            weight             = 500,
                        )
                    }
                    if (showSkips) {
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
            }

            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
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

/**
 * The artwork above the strip, square to the rail's own width.
 *
 * Square rather than the concept's fixed height: a rail is not one width, and a
 * band of a fixed height turns into a letterbox in a wide one and swallows the
 * card in a narrow one.
 */
@Composable
private fun Cover(artwork: ImageBitmap?, accent: androidx.compose.ui.graphics.Color) {
    val shape = MaterialTheme.shapes.small
    if (artwork != null) {
        Image(
            bitmap             = artwork,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(NxIcon.MusicNote, null, tint = accent, fill = 1f, weight = 500, modifier = Modifier.size(32.dp))
        }
    }
}

/**
 * How tall the strip and the words beside it are.
 *
 * Fixed, because the strip is the measure and a measure whose length depends on
 * how long the title happens to be is a measure that changes scale per track.
 */
private val BODY_HEIGHT = 96.dp

/** What a clock reads with nothing loaded. */
private const val EMPTY_CLOCK = "--:--"
