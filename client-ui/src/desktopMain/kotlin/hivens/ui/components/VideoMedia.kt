package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.hivens.skinema.compose.VideoScale
import hivens.launcher.media.VideoCacheService
import hivens.launcher.media.YtDlpService
import hivens.ui.i18n.LocalStrings
import hivens.ui.render.openInBrowser
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import java.nio.file.Path

/** Resolution state of a remote video URL to a local cached file. */
sealed interface VideoLoad {
    data object Loading : VideoLoad
    data class Ready(val path: Path) : VideoLoad
    data object Failed : VideoLoad
}

/**
 * Resolves [url] to a local cached file, as observable state. A direct video
 * file fetches through [VideoCacheService]; a service page (YouTube etc.)
 * downloads via [YtDlpService]. Either way the download lives in the service
 * scope, so leaving the composition cancels only the observation, not the fetch.
 */
@Composable
fun rememberCachedVideo(url: String): VideoLoad {
    val videoCache = koinInject<VideoCacheService>()
    val ytDlp = koinInject<YtDlpService>()
    val state by produceState<VideoLoad>(VideoLoad.Loading, url) {
        value = VideoLoad.Loading
        value = try {
            val path = if (isVideoServiceUrl(url)) ytDlp.resolve(url) else videoCache.resolve(url)
            VideoLoad.Ready(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VideoLoad.Failed
        }
    }
    return state
}

/**
 * A video by URL: shows [thumbUrl] (or a dark placeholder) with a spinner while
 * the file resolves (a service page can be slow -- it downloads whole), then
 * plays it with [VideoPlayer]. On failure it offers to open the page in a
 * browser instead. Skinema is local-only, so this resolve step is unavoidable.
 */
@Composable
fun VideoMedia(
    url: String,
    thumbUrl: String? = null,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    loop: Boolean = false,
    audio: Boolean = true,
    startMuted: Boolean = false,
    showControls: Boolean = true,
    scale: VideoScale = VideoScale.Fit,
    onRequestFullscreen: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    when (val load = rememberCachedVideo(url)) {
        is VideoLoad.Ready -> VideoPlayer(
            path                = load.path,
            modifier            = modifier,
            autoPlay            = autoPlay,
            loop                = loop,
            audio               = audio,
            startMuted          = startMuted,
            showControls        = showControls,
            scale               = scale,
            onRequestFullscreen = onRequestFullscreen,
        )
        VideoLoad.Loading -> PlaceholderBox(modifier, thumbUrl) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                Text(s.videoLoading, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
        }
        VideoLoad.Failed -> PlaceholderBox(modifier, thumbUrl) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.videoError, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                Text(
                    text     = s.videoOpenInBrowser,
                    color    = Color.White,
                    style    = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable { openInBrowser(url) }.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceholderBox(modifier: Modifier, thumbUrl: String?, overlay: @Composable () -> Unit) {
    Box(modifier.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        if (thumbUrl != null) {
            AsyncImage(
                model              = thumbUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}
