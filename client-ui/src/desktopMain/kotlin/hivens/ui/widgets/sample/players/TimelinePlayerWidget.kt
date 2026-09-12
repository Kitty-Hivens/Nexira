package hivens.ui.widgets.sample.players

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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
import hivens.widget.model.PropRange
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The body is the timeline.
 *
 * There is no progress bar on this card because the card itself is the measure:
 * the played part of the track is the filled part of the plane, and position is
 * read from how full the object is. That is one element doing one job, where a bar
 * is a second element competing with a volume track of the same shape a few
 * pixels away.
 *
 * It follows that the plane is also where a seek happens. A card whose body means
 * position and cannot be pressed to change it would be a measure that reads and
 * does not answer, so the whole surface takes the drag and the transport glyphs
 * sitting on top of it take their own clicks first.
 *
 * The shortest of the player kinds, and the one for a strip: no artwork, one row,
 * and a height that does not grow with what is loaded.
 */
@Serializable
data class TimelinePlayerProps(
    /**
     * How strongly the played part is inked. The concept's own value is 0.22:
     * enough to read the position across the card at a glance, little enough that
     * the text over the boundary keeps its contrast on either side of it.
     */
    @PropLabel("widget.home.new.player.timeline.fill") @PropRange(0.05, 0.5)
    val fill: Float = 0.22f,
)

@Widget(
    id = "home.new.player.timeline",
    displayName = "widget.home.new.player.timeline",
    propsClass = TimelinePlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun TimelinePlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<TimelinePlayerProps>()
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

    TimelinePlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        fill        = p.fill,
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
internal fun TimelinePlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    fill: Float,
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
    val fraction = progressFraction(state)

    NxSurface(
        level    = NxSurfaceLevel.Floating,
        modifier = modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(76.dp)) {
            // One threshold per element and no element consults another, so the
            // ladder is monotonic: the length goes first, then the elapsed, then
            // the skips. The credit line survives longest because it is the only
            // thing on the card that says WHAT is playing.
            val showTotal = maxWidth >= 320.dp
            val showElapsed = maxWidth >= 260.dp
            val skips = queueSize > 1 && maxWidth >= 220.dp

            // The measure, and the only one on the card.
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(lerp(palette.surfaceContainer, palette.primary, fill.coerceIn(0f, 1f))),
            )

            // The gesture layer sits UNDER the row: hit testing runs top down, so
            // the glyphs above consume their own presses and everything that lands
            // on the plane itself falls through to here. With nothing loaded the
            // same surface opens the picker, since there is no artwork to click and
            // a card that says "pick a track" has to answer somewhere.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(idle, loaded, duration) {
                        if (idle) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onPick()
                            }
                            return@pointerInput
                        }
                        if (!loaded || duration <= 0L) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val width = size.width.coerceAtLeast(1).toFloat()
                            onSeek(((down.position.x / width).coerceIn(0f, 1f) * duration).toLong())
                            drag(down.id) { change ->
                                onSeek(((change.position.x / width).coerceIn(0f, 1f) * duration).toLong())
                                change.consume()
                            }
                        }
                    },
            )

            Row(
                modifier          = Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val name = playerTitle(state, track, s)
                    Text(
                        text       = name,
                        style      = MaterialTheme.typography.bodyLarge,
                        color      = palette.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(name),
                    )
                    Spacer(Modifier.height(2.dp))
                    // Assembled from the parts that survived rather than formatted
                    // as one string and then cut: an ellipsis through a timecode
                    // leaves a number that is wrong rather than absent.
                    val credit = track?.artist ?: playerStatus(state, s)
                    // The clock is assembled from the labels that EXIST rather than
                    // formatted and then filtered. Both are empty with nothing
                    // loaded, and a blank check over the joined string passes " / ",
                    // which drew an empty fraction after a dangling separator.
                    val elapsed = elapsedLabel(state)
                    val total = totalLabel(state)
                    val clock = when {
                        !showElapsed || elapsed.isBlank() -> ""
                        showTotal && total.isNotBlank() -> "$elapsed / $total"
                        else -> elapsed
                    }
                    val line = listOf(credit, clock)
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  ")
                    Text(
                        text       = line,
                        style      = MaterialTheme.typography.bodySmall,
                        color      = palette.textSecondary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(line),
                    )
                }
                Spacer(Modifier.width(8.dp))
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
                // In the row rather than floating in a corner: this card is one
                // row tall and has no corner that is not already the row.
                Box {
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
