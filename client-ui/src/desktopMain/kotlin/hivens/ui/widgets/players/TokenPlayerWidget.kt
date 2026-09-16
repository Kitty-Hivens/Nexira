package hivens.ui.widgets.players

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCycleToggle
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxPanelGroup
import hivens.ui.nx.NxPopoverPanel
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxTooltip
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
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
 * A token: the smallest presence that still plays.
 *
 * One round grid cell and three things in it. The ring is the measure, the glyph
 * is the transport, and the name is not shown at all, because at this size a name
 * would be two truncated characters and the ring would have to give up room to
 * hold them. It is a hover away instead, anchored under the token rather than
 * chasing the pointer.
 *
 * This is the kind for somebody who wants a player on screen and does not want a
 * player on screen: it sits in a corner, it says how far in the track is, and it
 * answers a click. Everything else about it is reachable and nothing else about
 * it is drawn.
 *
 * The overflow is the one concession, and it only appears under the pointer. A
 * token with a permanent second control on it is no longer one thing, which is
 * the same argument the record makes about its own face.
 */
@Serializable
data class TokenPlayerProps(
    /**
     * Whether the artwork fills the middle instead of the transport glyph, with
     * the glyph arriving on hover over it. The token as a tiny cover rather than
     * as a control, for a placement where it is decoration first.
     */
    @PropLabel("widget.home.new.player.token.showCover") val showCover: Boolean = false,
    /**
     * How wide the token may be, in points. A ceiling rather than a size: a
     * slot narrower than this gets a smaller token instead of one that spills
     * out of it.
     */
    @PropRange(min = 48.0, max = 320.0)
    @PropLabel("widget.home.new.player.token.size") val size: Int = 96,
)

@Widget(
    id = "home.new.player.token",
    displayName = "widget.home.new.player.token",
    propsClass = TokenPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun TokenPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<TokenPlayerProps>()
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

    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    TokenPlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showCover   = p.showCover,
        maxSide     = p.size.coerceIn(48, 320).dp,
        chrome      = hovered,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
        onRepeat    = { player.setRepeat(it) },
        onSkipNext  = { player.skipToNext() },
        onSkipPrev  = { player.skipToPrevious() },
        onSeek      = { player.seek(it) },
        modifier    = Modifier.hoverable(hover),
    )
}

/**
 * The card over plain data, so it renders off-screen across palettes.
 *
 * [chrome] is a parameter for the same reason the tile's is: what the pointer
 * reveals is half the design, and a probe cannot produce a pointer.
 */
@Composable
internal fun TokenPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showCover: Boolean,
    maxSide: Dp = 96.dp,
    chrome: Boolean,
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

    val reveal by animateFloatAsState(
        targetValue = if (chrome || menuOpen || idle) 1f else 0f,
        label = "token-chrome",
    )

    val name = playerTitle(state, track, s)
    val artist = track?.artist
    val caption = if (artist.isNullOrBlank()) name else "$name  ·  $artist"

    // The cap goes on the tooltip's own box rather than on the surface inside it.
    // A tooltip is placed from the left edge of what it wraps, so a full-width box
    // around a centred token anchored the label at the edge of the slot: on a home
    // screen that put it most of a page away from the thing it names.
    NxTooltip(text = caption, modifier = modifier.playerObject(maxSide)) {
        NxSurface(
            level    = NxSurfaceLevel.Floating,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape    = CircleShape,
        ) {
            val duration = durationMsOf(state)
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // The ring is the measure, so it is the scrubber too: a press or a
                    // drag round it lands where the angle points. Inside the label
                    // there is nothing to seek to, only the transport.
                    .seekByAngle(MIDDLE_SHARE) { at ->
                        if (loaded && duration > 0L) onSeek((at * duration).toLong())
                    }
                    .openWhenEmpty(idle, s.audioPickTrack, onPick),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                Canvas(Modifier.fillMaxSize().padding(RING_PADDING)) {
                    val stroke = RING_STROKE.toPx()
                    val inset = stroke / 2f
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = palette.textSecondary.copy(alpha = 0.22f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    val played = progressFraction(state).coerceIn(0f, 1f)
                    if (played > 0f) {
                        drawArc(
                            color = palette.primary,
                            startAngle = -90f, sweepAngle = 360f * played, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }

                val middle = side * MIDDLE_SHARE
                if (showCover && track?.artwork != null) {
                    Image(
                        bitmap             = track.artwork,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.size(middle).clip(CircleShape),
                    )
                    // The glyph arrives over the artwork rather than instead of it,
                    // so the token stays a picture until somebody means to use it.
                    if (reveal > 0f) {
                        Box(
                            Modifier.size(middle).clip(CircleShape).alpha(reveal)
                                .background(Color.Black.copy(alpha = 0.46f)),
                            contentAlignment = Alignment.Center,
                        ) { Transport(state, loaded, Color.White, side, s, onPlayPause) }
                    }
                } else {
                    Transport(state, loaded, palette.textPrimary, side, s, onPlayPause)
                }

                if (side >= OVERFLOW_FLOOR) {
                    // Inset onto the ring rather than left in the corner of the
                    // bounding box, which on a circle is outside the shape and is
                    // therefore clipped away to a speck.
                    Box(Modifier.align(Alignment.TopEnd).padding(side * OVERFLOW_INSET).alpha(reveal)) {
                        NxIconButton(
                            icon               = NxIcon.MoreVert,
                            contentDescription = s.packCardMore,
                            onClick            = { menuOpen = true },
                            iconSize           = 16.dp,
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
                            // The face has no room for a skip, so the queue is
                            // reachable only from here.
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
            }
        }
    }
}

@Composable
private fun Transport(
    state: PlaybackState,
    loaded: Boolean,
    tint: Color,
    side: Dp,
    s: AppStrings,
    onPlayPause: () -> Unit,
) = NxIconButton(
    icon               = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
    contentDescription = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
    onClick            = onPlayPause,
    tint               = tint,
    enabled            = loaded,
    // Scaled with the cell rather than fixed: a token is placed at whatever size
    // its cell is, and a fixed glyph is a speck in a large one.
    iconSize           = (side * GLYPH_SHARE).coerceIn(14.dp, 40.dp),
    fill               = 1f,
    weight             = 500,
)

/** The middle's diameter as a share of the cell, inside the ring. */
private const val MIDDLE_SHARE = 0.68f

private const val GLYPH_SHARE = 0.27f

private val RING_PADDING = 11.dp

private val RING_STROKE = 5.dp

/**
 * Below this the overflow would sit on the ring and cover a quarter of it, so it
 * is dropped and the panel is reached from a larger instance or another widget.
 */
private val OVERFLOW_FLOOR = 84.dp

/** How far in from the corner the overflow sits, as a share of the cell. */
private const val OVERFLOW_INSET = 0.06f
