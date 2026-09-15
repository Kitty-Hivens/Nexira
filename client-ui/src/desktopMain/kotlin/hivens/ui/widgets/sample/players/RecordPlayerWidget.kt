package hivens.ui.widgets.sample.players

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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
import hivens.ui.nx.NxTooltip
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.widgets.sample.progressFraction
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
 * The record.
 *
 * One square cell and no words in it. The cover is a disc, the envelope radiates
 * around it, and the played share is the lit arc, so every part of the object is
 * doing one job and the whole thing reads at a glance from across the room. What
 * is playing lives in the tooltip rather than in a caption, which is the trade
 * this shape makes: a name is available on demand and never at the cost of the
 * only square it has.
 *
 * The envelope is the ring rather than a bar because a disc has no left and no
 * right to run a bar along, and an arc drawn plainly would say the same thing a
 * progress ring says on everything else. Bent into the ring it says two things
 * at once, the shape of the track and where in it we are.
 *
 * No skip controls on the face, for the same reason there is no caption: they
 * would be the fourth and fifth things on an object whose whole argument is that
 * it is one thing. The queue is steppable from the panel behind the overflow,
 * which is where everything that does not fit on this face goes.
 */
@Serializable
data class RecordPlayerProps(
    /**
     * Whether a caption sits under the disc after all. Off by default, which is
     * the concept as drawn: the tooltip is where the name belongs. On, for a
     * placement where nobody is going to hover.
     */
    @PropLabel("widget.home.new.player.record.showCaption") val showCaption: Boolean = false,
    /** How wide the disc may be, in points. A ceiling, not a size. */
    @PropLabel("widget.home.new.player.record.size") val size: Int = 168,
)

@Widget(
    id = "home.new.player.record",
    displayName = "widget.home.new.player.record",
    propsClass = RecordPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun RecordPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<RecordPlayerProps>()
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

    RecordPlayerCard(
        state       = state,
        track       = track,
        waveform    = waveform,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showCaption = p.showCaption,
        maxSide     = p.size.coerceIn(72, 420).dp,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
        onRepeat    = { player.setRepeat(it) },
        onSkipNext  = { player.skipToNext() },
        onSkipPrev  = { player.skipToPrevious() },
    )
}

/** The card over plain data, so it renders off-screen across palettes. */
@Composable
internal fun RecordPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    waveform: Waveform?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showCaption: Boolean,
    maxSide: Dp = 168.dp,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val palette = NxTheme.colors
    val idle = state is PlaybackState.Idle
    val loaded = !idle && state !is PlaybackState.Error
    var menuOpen by remember { mutableStateOf(false) }

    val name = playerTitle(state, track, s)
    val artist = track?.artist
    val caption = if (artist.isNullOrBlank()) name else "$name  ·  $artist"

    Column(modifier.playerObject(maxSide), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            NxTooltip(text = caption, enabled = !showCaption) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        // The disc is the only affordance this shape has, so with
                        // nothing loaded it has to be the one that opens a file.
                        .then(if (idle) Modifier.clickable(onClick = onPick) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight)
                    val innerPx = with(LocalDensity.current) { (side * LABEL_SHARE / 2f).toPx() }
                    WaveformRing(
                        waveform = waveform,
                        fraction = progressFraction(state),
                        played = palette.primary,
                        remaining = palette.textSecondary.copy(alpha = 0.30f),
                        // The ring starts where the label ends, with a hair of air
                        // between them so the bars do not appear to grow out of the
                        // artwork itself.
                        innerRadius = innerPx + with(LocalDensity.current) { 3.dp.toPx() },
                        modifier = Modifier.fillMaxSize().padding(RING_INSET),
                    )
                    Label(track?.artwork, side * LABEL_SHARE, palette.primary)
                    Box(
                        Modifier
                            .size(side * BUTTON_SHARE)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = TRANSPORT_SCRIM)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NxIconButton(
                            icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                            contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                            onClick            = onPlayPause,
                            tint               = Color.White,
                            enabled            = loaded,
                            iconSize           = side * BUTTON_SHARE / 2f,
                            fill               = 1f,
                            weight             = 500,
                        )
                    }
                }
            }

            Box(Modifier.align(Alignment.TopEnd)) {
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
                    // The face carries no skips, so a queue would be unreachable
                    // from a screen holding only this widget. They live here.
                    if (queueSize > 1) {
                        NxPanelGroup(label = s.audioQueue) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NxIconButton(
                                    icon               = NxIcon.SkipPrevious,
                                    contentDescription = s.audioSkipPrevious,
                                    onClick            = onSkipPrev,
                                    enabled            = loaded,
                                )
                                NxIconButton(
                                    icon               = NxIcon.SkipNext,
                                    contentDescription = s.audioSkipNext,
                                    onClick            = onSkipNext,
                                    enabled            = loaded,
                                )
                            }
                        }
                    }
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

        if (showCaption) {
            Text(
                text       = name,
                style      = MaterialTheme.typography.bodySmall,
                color      = palette.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = TextAlign.Center,
                fontFamily = familyForText(name),
                modifier   = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** The disc at the middle: the artwork, or a tonal stand-in where there is none. */
@Composable
private fun Label(artwork: androidx.compose.ui.graphics.ImageBitmap?, side: androidx.compose.ui.unit.Dp, accent: Color) {
    if (artwork != null) {
        Image(
            bitmap             = artwork,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.size(side).clip(CircleShape),
        )
    } else {
        Box(
            Modifier.size(side).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(NxIcon.MusicNote, null, tint = accent, fill = 1f, weight = 500, modifier = Modifier.size(side / 3f))
        }
    }
}

/** The label's diameter as a share of the square. The rest is ring and air. */
private const val LABEL_SHARE = 0.57f

/** The transport disc over the label, sized so it covers a label and not the ring. */
private const val BUTTON_SHARE = 0.26f

/** Keeps the loudest bar off the edge of the cell. */
private val RING_INSET = 4.dp

/**
 * What the transport disc puts between itself and the label under it.
 *
 * Heavier than the concept sheet's, which was drawn over a dark blue cover
 * and read fine there. A label with a bright middle, which is most of them,
 * left a white glyph on yellow with a wash that did nothing.
 */
private const val TRANSPORT_SCRIM = 0.58f
