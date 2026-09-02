package hivens.ui.widgets.sample

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioError
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxProgressBar
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.utils.pickFile
import hivens.ui.utils.rememberFileDialogSettings
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

// Mini music player. Drop an audio file, play it through AudioPlayer
// (Skinema / FFmpeg via Panama -- mp3, flac, ogg, opus, aac, wav). The
// opening pin in the project_achievements vision -- the user can compose
// the launcher into a music-only surface by removing every game widget
// and keeping this + a clock + the right rail (or nothing).
@Serializable
data class MusicProps(
    @PropLabel("widget.home.new.music.title") val title: String = "",
)

private val AUDIO_EXTENSIONS = listOf(
    "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "aiff", "aif", "au",
)

@Widget(id = "home.new.music", displayName = "widget.home.new.music", propsClass = MusicProps::class, drawsOwnSurface = true)
@ProvidesService(MusicPlayerService::class)
@Composable
fun MusicPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<MusicProps>()
    val s = LocalStrings.current
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val track by player.track.collectAsState()
    val scope = rememberCoroutineScope()

    // Expose this widget's AudioPlayer-backed playback through the
    // cross-widget service registry. PlaybackMiniControlWidget (and
    // future achievement watchers / music-ducking helpers) read this
    // via useService<MusicPlayerService>(). The DisposableEffect
    // unregisters on dispose so removing the widget cleanly drops
    // the binding; re-adding it re-binds to the same AudioPlayer
    // singleton.
    val musicService = remember(player) { MusicPlayerServiceImpl(player) }
    provideService(MusicPlayerService::class, instance.instanceId, musicService)

    val dialogSettings = rememberFileDialogSettings(s.audioPickTrack)
    val openTrack = {
        scope.launch {
            val picked = pickFile(
                type     = FileKitType.File(extensions = AUDIO_EXTENSIONS),
                settings = dialogSettings,
            )
            val path = picked?.path?.let { Paths.get(it) }
            if (path != null) player.open(path)
        }
        Unit
    }

    MusicPlayerCard(
        heading     = p.title.ifBlank { s.musicPlayerTitle },
        state       = state,
        track       = track,
        volume      = volume,
        onPick      = { openTrack() },
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
    )
}

/**
 * The card itself, over plain data. Split from the widget so what is drawn can be
 * rendered off-screen across styles and palettes without a Koin graph or a live
 * decode thread behind it -- the same split the activity pill uses.
 */
@Composable
internal fun MusicPlayerCard(
    heading: String,
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // A plane from the library rather than a hand-mixed fill: the tonal body and the
    // legibility floor over a wallpaper come with the level.
    NxSurface(NxSurfaceLevel.Floating, modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header + album-art block + transport
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                AlbumArtBlock(track)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = heading,
                        style      = MaterialTheme.typography.labelLarge,
                        color      = NxTheme.colors.textSecondary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text       = currentTitle(state, track, s),
                        style      = MaterialTheme.typography.titleMedium,
                        color      = NxTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (state is PlaybackState.Error) {
                        Text(
                            text  = audioErrorText(state.reason, s),
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.error,
                        )
                    } else {
                        Text(
                            text     = subtitle(state, track, s),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = NxTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // The measure primitive, not Material's: no stop-indicator dot, and its
            // corner follows the style axis. Its own accent role also keeps it from
            // reading as a second volume bar.
            NxProgressBar(progress = progressFraction(state), height = 3.dp)

            // Controls row: transport on the left, volume + timecode on the right.
            // The volume bar is a thin custom track (the Material slider's fat thumb
            // reads as an out-of-place form control here).
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                ControlButton(
                    icon            = NxIcon.FolderOpen,
                    contentDesc     = s.audioOpenFile,
                    onClick         = onPick,
                )
                ControlButton(
                    icon         = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                    contentDesc  = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                    enabled      = state !is PlaybackState.Idle && state !is PlaybackState.Error,
                    primary      = true,
                    onClick      = onPlayPause,
                )
                ControlButton(
                    icon         = NxIcon.Stop,
                    contentDesc  = s.audioStop,
                    enabled      = state is PlaybackState.Playing || state is PlaybackState.Paused,
                    onClick      = onStop,
                )
                Spacer(Modifier.weight(1f))
                Symbol(icon = volumeIcon(volume),
                    contentDescription = s.audioVolume,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(16.dp),
                )
                VolumeBar(
                    value         = volume,
                    onValueChange = onVolume,
                    modifier      = Modifier.width(96.dp),
                )
                val timeline = timelineLabel(state)
                if (timeline.isNotEmpty()) {
                    Text(
                        text  = timeline,
                        style = MaterialTheme.typography.labelSmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

// Thin horizontal track + small dot handle. The track grows from 3dp to 4dp and
// the handle from 10dp to 12dp under the pointer, so the feedback is size rather
// than appearance: the handle is solid at rest and does not have to be found.
// Drag updates the value continuously; tap jumps to the tapped position.
@Composable
private fun VolumeBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var pressing by remember { mutableStateOf(false) }
    val active = isHovered || pressing

    val trackHeight by animateDpAsState(
        targetValue   = if (active) 4.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "vol-track-height",
    )
    val thumbSizeDp by animateDpAsState(
        targetValue   = if (pressing) 12.dp else 10.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "vol-thumb-size",
    )

    var widthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .height(20.dp)
            .hoverable(interaction)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressing = true
                    // finally: a cancelled/interrupted drag must still release
                    // the pressed state, or the thumb sticks enlarged.
                    try {
                        val w = size.width.coerceAtLeast(1).toFloat()
                        onValueChange((down.position.x / w).coerceIn(0f, 1f))
                        drag(down.id) { change ->
                            val newValue = (change.position.x / w).coerceIn(0f, 1f)
                            onValueChange(newValue)
                            change.consume()
                        }
                    } finally {
                        pressing = false
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Inactive track (full bar background).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(NxTheme.colors.outline.copy(alpha = 0.20f)),
        )
        // Active fill (left edge to current value).
        Box(
            modifier = Modifier
                .fillMaxWidth(value)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(NxTheme.colors.primary),
        )
        // The handle at the active edge. Drawn in the colour meant to be read ON the
        // accent rather than in the accent itself: a translucent dot of the same hue
        // as the fill it sits on cannot read as a handle, only as a thinner patch of
        // the bar, and at full volume half of it hangs off the track onto the card,
        // so one circle was compositing over two different grounds. Solid, and it
        // contrasts by construction on either palette.
        if (widthPx > 0) {
            val thumbHalfPx = with(LocalDensity.current) { thumbSizeDp.toPx() / 2f }
            val xPx = (value * widthPx - thumbHalfPx).toInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, 0) }
                    .size(thumbSizeDp)
                    .clip(CircleShape)
                    .background(NxTheme.colors.onPrimary),
            )
        }
    }
}

private fun volumeIcon(volume: Float): IconKey = when {
    volume <= 0.001f -> NxIcon.VolumeOff
    volume < 0.34f   -> NxIcon.VolumeMute
    volume < 0.67f   -> NxIcon.VolumeDown
    else             -> NxIcon.VolumeUp
}

/**
 * The track's own cover where the file carries one, and the note glyph on a
 * tinted square where it does not -- which for a folder of game soundtracks is
 * most of them, so the fallback is the normal case rather than an error state.
 */
@Composable
private fun AlbumArtBlock(track: TrackInfo?) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NxTheme.colors.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = track?.artwork
        if (artwork != null) {
            Image(
                bitmap             = artwork,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Symbol(icon = NxIcon.MusicNote,
                contentDescription = null,
                tint               = NxTheme.colors.primary,
                modifier           = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: IconKey,
    contentDesc: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> NxTheme.colors.surfaceVariant.copy(alpha = 0.4f)
        primary  -> NxTheme.colors.primary
        else     -> NxTheme.colors.surface
    }
    val tint = when {
        !enabled -> NxTheme.colors.textSecondary.copy(alpha = 0.4f)
        primary  -> Color.White
        else     -> NxTheme.colors.textPrimary
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon = icon,
            contentDescription = contentDesc,
            tint               = tint,
            modifier           = Modifier.size(if (primary) 20.dp else 16.dp),
        )
    }
}

private fun audioErrorText(reason: AudioError, s: AppStrings): String = when (reason) {
    AudioError.UnsupportedFormat -> s.audioErrorUnsupported
    AudioError.OpenFailed        -> s.audioErrorOpenFailed
    AudioError.DeviceBusy        -> s.audioErrorDeviceBusy
    AudioError.PlaybackFailed    -> s.audioErrorPlaybackFailed
}

/** The track's own title, falling back to the file name until metadata lands. */
private fun currentTitle(state: PlaybackState, track: TrackInfo?, s: AppStrings): String = when (state) {
    is PlaybackState.Idle    -> s.audioPickTrack
    is PlaybackState.Ready   -> track?.title ?: state.file.name
    is PlaybackState.Playing -> track?.title ?: state.file.name
    is PlaybackState.Paused  -> track?.title ?: state.file.name
    is PlaybackState.Error   -> state.file.name
}

/**
 * Who made the track, or -- for a file that does not say -- what the transport
 * is doing. The status text is the fallback rather than the subtitle: with a
 * real artist to show, "Playing" is already on screen as the transport icon.
 */
private fun subtitle(state: PlaybackState, track: TrackInfo?, s: AppStrings): String {
    if (state is PlaybackState.Idle) return s.audioFormatHint
    val credit = listOfNotNull(track?.artist, track?.album).joinToString(" · ")
    if (credit.isNotEmpty()) return credit
    return when (state) {
        is PlaybackState.Ready   -> s.audioStatusReady
        is PlaybackState.Playing -> s.audioStatusPlaying
        is PlaybackState.Paused  -> s.audioStatusPaused
        else                     -> ""
    }
}

