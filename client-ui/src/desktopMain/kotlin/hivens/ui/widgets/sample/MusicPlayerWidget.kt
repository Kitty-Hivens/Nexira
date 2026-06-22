package hivens.ui.widgets.sample

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioError
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Widget(id = "home.new.music", displayName = "widget.home.new.music", propsClass = MusicProps::class)
@ProvidesService(MusicPlayerService::class)
@Composable
fun MusicPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<MusicProps>()
    val s = LocalStrings.current
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
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

    val pickFile = {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                FileKit.openFilePicker(
                    type           = FileKitType.File(extensions = listOf(
                        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "aiff", "aif", "au",
                    )),
                    dialogSettings = FileKitDialogSettings(title = s.audioPickTrack),
                )
            }
            val path = picked?.path?.let { Paths.get(it) }
            if (path != null) player.open(path)
        }
        Unit
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        NxTheme.colors.surface,
                        glassSurfaceAlpha(0.55f),
                    ),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header + album-art block + transport
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AlbumArtBlock(state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = p.title.ifBlank { s.musicPlayerTitle },
                    style      = MaterialTheme.typography.labelLarge,
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = currentTitle(state, s),
                    style      = MaterialTheme.typography.titleMedium,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (state is PlaybackState.Error) {
                    Text(
                        text  = audioErrorText((state as PlaybackState.Error).reason, s),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.error,
                    )
                } else {
                    Text(
                        text     = subtitle(state, s),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = NxTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Progress strip
        val fraction = progressFraction(state)
        LinearProgressIndicator(
            progress   = { fraction },
            modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color      = NxTheme.colors.primary,
            trackColor = NxTheme.colors.outline.copy(alpha = 0.15f),
        )

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
                onClick         = { pickFile() },
            )
            ControlButton(
                icon         = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                contentDesc  = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                enabled      = state !is PlaybackState.Idle && state !is PlaybackState.Error,
                primary      = true,
                onClick      = {
                    if (state is PlaybackState.Playing) player.pause() else player.play()
                },
            )
            ControlButton(
                icon         = NxIcon.Stop,
                contentDesc  = s.audioStop,
                enabled      = state is PlaybackState.Playing || state is PlaybackState.Paused,
                onClick      = { player.stop() },
            )
            Spacer(Modifier.weight(1f))
            Symbol(icon = volumeIcon(volume),
                contentDescription = s.audioVolume,
                tint               = NxTheme.colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
            VolumeBar(
                value         = volume,
                onValueChange = { player.setVolume(it) },
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

// Thin horizontal track + small dot thumb. Track grows from 3dp to
// 4dp on hover; thumb fades in on hover/press. Drag updates the value
// continuously; tap jumps to the tapped position. Designed to read as
// a player slider rather than a generic form control.
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
    // Always faintly visible so the handle is findable without hunting; full
    // opacity on hover/drag.
    val thumbAlpha by animateFloatAsState(
        targetValue   = if (active) 1f else 0.65f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "vol-thumb-alpha",
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
        // Thumb dot at the active edge -- only visible on hover/press.
        if (widthPx > 0 && thumbAlpha > 0.01f) {
            val thumbHalfPx = with(LocalDensity.current) { thumbSizeDp.toPx() / 2f }
            val xPx = (value * widthPx - thumbHalfPx).toInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, 0) }
                    .size(thumbSizeDp)
                    .graphicsLayer { alpha = thumbAlpha }
                    .clip(CircleShape)
                    .background(NxTheme.colors.primary),
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

@Composable
private fun AlbumArtBlock(state: PlaybackState) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NxTheme.colors.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon = NxIcon.MusicNote,
            contentDescription = null,
            tint               = NxTheme.colors.primary,
            modifier           = Modifier.size(28.dp),
        )
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

private fun currentTitle(state: PlaybackState, s: AppStrings): String = when (state) {
    is PlaybackState.Idle    -> s.audioPickTrack
    is PlaybackState.Ready   -> state.file.name
    is PlaybackState.Playing -> state.file.name
    is PlaybackState.Paused  -> state.file.name
    is PlaybackState.Error   -> state.file.name
}

private fun subtitle(state: PlaybackState, s: AppStrings): String = when (state) {
    PlaybackState.Idle       -> s.audioFormatHint
    is PlaybackState.Ready   -> s.audioStatusReady
    is PlaybackState.Playing -> s.audioStatusPlaying
    is PlaybackState.Paused  -> s.audioStatusPaused
    is PlaybackState.Error   -> ""
}

private fun progressFraction(state: PlaybackState): Float = when (state) {
    is PlaybackState.Playing -> safeFraction(state.positionMs, state.durationMs)
    is PlaybackState.Paused  -> safeFraction(state.positionMs, state.durationMs)
    is PlaybackState.Ready   -> safeFraction(state.positionMs, state.durationMs)
    else                     -> 0f
}

private fun safeFraction(position: Long, duration: Long): Float =
    if (duration <= 0L) 0f else (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

private fun timelineLabel(state: PlaybackState): String {
    val (pos, dur) = when (state) {
        is PlaybackState.Playing -> state.positionMs to state.durationMs
        is PlaybackState.Paused  -> state.positionMs to state.durationMs
        is PlaybackState.Ready   -> 0L to state.durationMs
        else                     -> return ""
    }
    if (dur <= 0L) return ""
    return "${formatMs(pos)} / ${formatMs(dur)}"
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
