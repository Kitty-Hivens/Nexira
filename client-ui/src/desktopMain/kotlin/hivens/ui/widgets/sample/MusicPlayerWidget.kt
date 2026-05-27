package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Paths
import kotlin.io.path.name

// Mini music player. Drop a WAV / AU / AIFF file, play it through
// AudioPlayer. The opening pin in the project_achievements vision --
// the user can compose the launcher into a music-only surface by
// removing every game widget and keeping this + a clock + the right
// rail (or nothing). MP3 support arrives with Skinema (FFmpeg
// via Panama).
@Widget(id = "home.new.music", displayName = "Music player")
@Composable
fun MusicPlayerWidget(instance: WidgetInstance) {
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    val pickFile = {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                FileKit.openFilePicker(
                    type           = FileKitType.File(extensions = listOf("wav", "au", "aif", "aiff", "snd")),
                    dialogSettings = FileKitDialogSettings(title = "Выбери трек"),
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
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        CelestiaTheme.colors.surface,
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
                    text       = "Музыкальный плеер",
                    style      = MaterialTheme.typography.labelLarge,
                    color      = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = currentTitle(state),
                    style      = MaterialTheme.typography.titleMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (state is PlaybackState.Error) {
                    Text(
                        text  = (state as PlaybackState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.error,
                    )
                } else {
                    Text(
                        text     = subtitle(state),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = CelestiaTheme.colors.textSecondary,
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
            color      = CelestiaTheme.colors.primary,
            trackColor = CelestiaTheme.colors.outline.copy(alpha = 0.15f),
        )

        // Controls row
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            ControlButton(
                icon            = Icons.Default.FolderOpen,
                contentDesc     = "Открыть файл",
                onClick         = { pickFile() },
            )
            ControlButton(
                icon         = if (state is PlaybackState.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDesc  = if (state is PlaybackState.Playing) "Пауза" else "Воспроизвести",
                enabled      = state !is PlaybackState.Idle && state !is PlaybackState.Error,
                primary      = true,
                onClick      = {
                    if (state is PlaybackState.Playing) player.pause() else player.play()
                },
            )
            ControlButton(
                icon         = Icons.Default.Stop,
                contentDesc  = "Стоп",
                enabled      = state is PlaybackState.Playing || state is PlaybackState.Paused,
                onClick      = { player.stop() },
            )
            Spacer(Modifier.weight(1f))
            val timeline = timelineLabel(state)
            if (timeline.isNotEmpty()) {
                Text(
                    text  = timeline,
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AlbumArtBlock(state: PlaybackState) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Default.MusicNote,
            contentDescription = null,
            tint               = CelestiaTheme.colors.primary,
            modifier           = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.4f)
        primary  -> CelestiaTheme.colors.primary
        else     -> CelestiaTheme.colors.surface
    }
    val tint = when {
        !enabled -> CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f)
        primary  -> Color.White
        else     -> CelestiaTheme.colors.textPrimary
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDesc,
            tint               = tint,
            modifier           = Modifier.size(if (primary) 20.dp else 16.dp),
        )
    }
}

private fun currentTitle(state: PlaybackState): String = when (state) {
    is PlaybackState.Idle    -> "Выбери трек"
    is PlaybackState.Ready   -> state.file.name
    is PlaybackState.Playing -> state.file.name
    is PlaybackState.Paused  -> state.file.name
    is PlaybackState.Error   -> state.file.name
}

private fun subtitle(state: PlaybackState): String = when (state) {
    PlaybackState.Idle       -> "WAV / AU / AIFF поддерживаются. MP3 будет в Skinema."
    is PlaybackState.Ready   -> "Готов"
    is PlaybackState.Playing -> "Играет"
    is PlaybackState.Paused  -> "Пауза"
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
