package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hivens.skinema.compose.VideoScale
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.VideoHandoff
import hivens.ui.components.VideoMedia
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class VideoPlayerProps(
    @PropLabel("widget.home.new.video.url") val videoUrl: String = "",
)

/**
 * A small inline video player for the layout: paste a video URL (direct file or
 * a YouTube/Vimeo page) and it plays in place, with an expand button for the
 * full-launcher view. Play is click-gated so a placed widget does not download
 * on mount, and the inline player is dropped while expanded so only one decode
 * runs at a time.
 */
@Widget(id = "home.new.video", displayName = "widget.home.new.video", propsClass = VideoPlayerProps::class)
@Composable
fun VideoPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<VideoPlayerProps>()
    val s = LocalStrings.current
    val url = p.videoUrl.trim()

    var playing by remember(url) { mutableStateOf(false) }
    var expanded by remember(url) { mutableStateOf(false) }
    // Owned here, above the swap, so the inline player and the fullscreen one are
    // two views of one viewing rather than two separate starts.
    val handoff = remember(url) { VideoHandoff() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url.isBlank() || !isPlayableVideoUrl(url) -> EmptyState(s.videoWidgetEmpty)
            // While expanded, drop the inline player so only the fullscreen one decodes.
            playing && !expanded -> VideoMedia(
                url                 = url,
                modifier            = Modifier.fillMaxSize(),
                autoPlay            = true,
                loop                = false,
                audio               = true,
                showControls        = true,
                scale               = VideoScale.Fit,
                handoff             = handoff,
                onRequestFullscreen = { expanded = true },
                // Stopping the download drops back to the poster, which is where
                // pressing play again starts from.
                onCancelled         = { playing = false },
            )
            else -> PlayPoster(onClick = { playing = true })
        }
    }

    if (expanded) {
        FullscreenVideo(url = url, handoff = handoff, onDismiss = { expanded = false })
    }
}

@Composable
private fun PlayPoster(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(NxIcon.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun EmptyState(hint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol(NxIcon.PlayArrow, contentDescription = null, tint = NxTheme.colors.textSecondary.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
        Text(
            text     = hint,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
