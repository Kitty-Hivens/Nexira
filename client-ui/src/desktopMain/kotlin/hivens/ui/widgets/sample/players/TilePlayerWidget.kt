package hivens.ui.widgets.sample.players

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * The cover is the whole object.
 *
 * At rest there is nothing but the picture and the name across the foot of it,
 * the way a video thumbnail shows nothing but the frame. The transport is not
 * drawn until the pointer is over the tile, so a wall of these is a wall of
 * artwork rather than a wall of chrome, and the one being pointed at is the one
 * that turns into a player.
 *
 * The dim under the chrome is flat rather than a gradient, which is the opposite
 * of what the caption gets, and deliberately: the controls sit in the MIDDLE of
 * the square, where a bottom gradient leaves them on whatever the cover happens
 * to be. The caption is at the foot, so a gradient is exactly right there, and it
 * is drawn whether the chrome is up or not, because the title sits on the picture
 * either way.
 *
 * With no artwork the ground is generated from the palette rather than filled
 * with a note glyph on grey. The empty case of a shape whose whole subject is a
 * picture should be a colour, not a picture apologising for not being one.
 */
@Serializable
data class TilePlayerProps(
    /**
     * Whether the name is written across the foot at rest. Off leaves nothing but
     * the artwork until the pointer arrives, which is the tile at its purest and
     * a poor deal for a corpus where half the files have no cover to recognise.
     */
    @PropLabel("widget.home.new.player.tile.showCaption") val showCaption: Boolean = true,
    /** How wide the tile may be, in points. A ceiling, not a size. */
    @PropLabel("widget.home.new.player.tile.size") val size: Int = 196,
)

@Widget(
    id = "home.new.player.tile",
    displayName = "widget.home.new.player.tile",
    propsClass = TilePlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun TilePlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<TilePlayerProps>()
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

    TilePlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showCaption = p.showCaption,
        maxSide     = p.size.coerceIn(72, 480).dp,
        chrome      = hovered,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
        onRepeat    = { player.setRepeat(it) },
        onSkipNext  = { player.skipToNext() },
        onSkipPrev  = { player.skipToPrevious() },
        modifier    = Modifier.hoverable(hover),
    )
}

/**
 * The card over plain data, so it renders off-screen across palettes.
 *
 * [chrome] is a parameter rather than hover state read in here, for the reason
 * the concept sheet drew both: the difference between the two is the whole design
 * of this shape, and a state only a pointer can produce is a state no probe can
 * photograph.
 */
@Composable
internal fun TilePlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showCaption: Boolean,
    maxSide: Dp = 196.dp,
    chrome: Boolean,
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

    // Faded rather than switched. The chrome appearing on a frame boundary reads
    // as a flicker when the pointer crosses a wall of these, and the menu holds it
    // up so a click into the panel does not take it away on the way there.
    val reveal by animateFloatAsState(
        targetValue = if (chrome || menuOpen || idle) 1f else 0f,
        label = "tile-chrome",
    )

    BoxWithConstraints(
        modifier
            .playerObject(maxSide)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .then(if (idle) Modifier.clickable(onClick = onPick) else Modifier),
    ) {
        val side = minOf(maxWidth, maxHeight)
        // One threshold, and it is about the words rather than the controls: a
        // tile small enough that a caption is two truncated characters is better
        // off as artwork alone, and the name is still a hover away.
        val caption = showCaption && side >= 132.dp
        val transport = side >= 96.dp

        Ground(track?.artwork, palette.surfaceContainer, palette.primary, palette.tertiary)

        if (reveal > 0f && transport) {
            Box(
                Modifier.fillMaxSize().alpha(reveal).background(Color.Black.copy(alpha = 0.42f)),
            )
            Row(
                Modifier.align(Alignment.Center).alpha(reveal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (queueSize > 1 && side >= 132.dp) {
                    NxIconButton(
                        icon               = NxIcon.SkipPrevious,
                        contentDescription = s.audioSkipPrevious,
                        onClick            = onSkipPrev,
                        tint               = Color.White.copy(alpha = 0.85f),
                        enabled            = loaded,
                        iconSize           = 20.dp,
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
                    iconSize           = 30.dp,
                    fill               = 1f,
                    weight             = 500,
                )
                if (queueSize > 1 && side >= 132.dp) {
                    NxIconButton(
                        icon               = NxIcon.SkipNext,
                        contentDescription = s.audioSkipNext,
                        onClick            = onSkipNext,
                        tint               = Color.White.copy(alpha = 0.85f),
                        enabled            = loaded,
                        iconSize           = 20.dp,
                        fill               = 1f,
                        weight             = 500,
                    )
                }
            }
        }

        if (caption) {
            // For the caption alone, and present whether the chrome is up or not,
            // because the title sits on the picture either way.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp).padding(bottom = 4.dp)) {
                val name = playerTitle(state, track, s)
                Text(
                    text       = name,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    fontFamily = familyForText(name),
                )
                val under = track?.artist
                if (!under.isNullOrBlank()) {
                    Text(
                        text       = under,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = Color.White.copy(alpha = 0.75f),
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(under),
                    )
                }
            }
        }

        // Inset by the shape's own corner so neither end of the line is eaten by
        // the curve, which is what a full-bleed rule at the foot of a rounded tile
        // looks like it is.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.26f)))
            Box(
                Modifier
                    .fillMaxWidth(progressFraction(state).coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(palette.primary),
            )
        }

        Box(Modifier.align(Alignment.TopEnd).alpha(reveal)) {
            NxIconButton(
                icon               = NxIcon.MoreVert,
                contentDescription = s.packCardMore,
                onClick            = { menuOpen = true },
                tint               = Color.White.copy(alpha = 0.85f),
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

/** The artwork, or a field generated from the palette where there is none. */
@Composable
private fun Ground(artwork: ImageBitmap?, base: Color, primary: Color, tertiary: Color) {
    if (artwork != null) {
        Image(
            bitmap             = artwork,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
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
