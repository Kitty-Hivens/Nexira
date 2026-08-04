package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.hivens.skinema.compose.VideoScale
import hivens.media.MediaFetch
import hivens.media.MediaResolver
import hivens.media.VideoCacheService
import hivens.media.YtDlpService
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxProgressBar
import hivens.ui.render.openInBrowser
import hivens.ui.utils.humanSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.compose.koinInject
import java.nio.file.Path

/** Resolution state of a remote video URL to a local cached file. */
sealed interface VideoLoad {

    /** Not playable yet; [fetch] is what that wait currently consists of. */
    data class Loading(val fetch: MediaFetch) : VideoLoad

    data class Ready(val path: Path) : VideoLoad

    data object Failed : VideoLoad

    /** The viewer stopped the fetch. Distinct from [Failed]: nothing went wrong. */
    data object Cancelled : VideoLoad
}

/**
 * A URL's resolution, plus the two things a viewer needs to do about it. Held
 * together because a progress readout with no way to stop the work is the state
 * this replaced.
 */
@Stable
class VideoResolution internal constructor(
    val load: VideoLoad,
    /** Stops the fetch. The state settles on [VideoLoad.Cancelled]. */
    val cancel: () -> Unit,
    /** Starts over after a failure or a cancellation. */
    val retry: () -> Unit,
)

/**
 * Resolves [url] to a local cached file, as observable state. A direct video
 * file fetches through [VideoCacheService]; a service page (YouTube etc.)
 * downloads via [YtDlpService]. Either way the download lives in the service
 * scope, so leaving the composition cancels only the observation -- [cancel] is
 * what stops the work itself.
 */
@Composable
fun rememberVideoResolution(url: String): VideoResolution {
    val videoCache = koinInject<VideoCacheService>()
    val ytDlp = koinInject<YtDlpService>()
    val resolver: MediaResolver = remember(url, videoCache, ytDlp) {
        if (isVideoServiceUrl(url)) ytDlp else videoCache
    }

    val fetch by resolver.fetchState(url).collectAsState()
    var attempt by remember(url) { mutableIntStateOf(0) }
    var outcome by remember(url) { mutableStateOf<VideoLoad?>(null) }

    LaunchedEffect(url, resolver, attempt) {
        outcome = null
        outcome = try {
            VideoLoad.Ready(resolver.resolve(url))
        } catch (e: CancellationException) {
            // Two different cancellations arrive here. This composition going
            // away must propagate, or the effect would report a state nobody is
            // watching; the fetch being stopped on purpose is a state to render.
            currentCoroutineContext().ensureActive()
            VideoLoad.Cancelled
        } catch (e: Exception) {
            VideoLoad.Failed
        }
    }

    // Until the resolve settles, the live fetch phase IS the state.
    val load = outcome ?: VideoLoad.Loading(fetch)
    return remember(load, resolver, url) {
        VideoResolution(load, cancel = { resolver.cancel(url) }, retry = { attempt++ })
    }
}

/**
 * A video by URL: shows [thumbUrl] (or a dark placeholder) while the file
 * resolves -- naming what it is waiting on, measuring it where the size is
 * known, and offering to stop -- then plays it with [VideoPlayer]. On failure it
 * offers a retry and the page in a browser. Skinema is local-only, so this
 * resolve step is unavoidable.
 *
 * [onCancelled] lets a caller that has somewhere to go back to (a poster, a
 * closing overlay) take it instead of showing the stopped state in place.
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
    onCancelled: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val resolution = rememberVideoResolution(url)
    when (val load = resolution.load) {
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
        // A caller that asked for no transport gets no fetch chrome either: an
        // ambient banner loops behind the page, and a progress readout with a
        // cancel over it is an interruption nobody asked for.
        is VideoLoad.Loading -> PlaceholderBox(modifier, thumbUrl) {
            if (showControls) FetchStatus(load.fetch, onCancel = resolution.cancel)
        }
        VideoLoad.Cancelled -> {
            if (onCancelled != null) LaunchedEffect(url) { onCancelled() }
            PlaceholderBox(modifier, thumbUrl) {
                if (showControls) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.videoCancelled, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        LinkText(s.videoRetry, resolution.retry)
                    }
                }
            }
        }
        VideoLoad.Failed -> PlaceholderBox(modifier, thumbUrl) {
            if (showControls) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.videoError, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    LinkText(s.videoRetry, resolution.retry)
                    LinkText(s.videoOpenInBrowser) { openInBrowser(url) }
                }
            }
        }
    }
}

/**
 * What the wait consists of: the phase in words, a measure where the size is
 * known (an indeterminate sweep where it is not), the byte counters once they
 * exist, and the way out.
 */
@Composable
internal fun FetchStatus(fetch: MediaFetch, onCancel: () -> Unit) {
    val s = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = fetchLabel(fetch, s),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
        )
        NxProgressBar(progress = fetch.fraction, modifier = Modifier.width(180.dp))
        byteLabel(fetch)?.let {
            Text(
                text  = it,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        LinkText(s.videoCancelDownload, onCancel)
    }
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Text(
        text     = label,
        color    = Color.White,
        style    = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline),
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp),
    )
}

private fun fetchLabel(fetch: MediaFetch, s: AppStrings): String = when (fetch) {
    // Idle here means the resolve has not reached the service yet; from the
    // outside that is still the generic wait.
    MediaFetch.Idle              -> s.videoLoading
    is MediaFetch.InstallingTool -> s.videoFetchingTool
    MediaFetch.Resolving         -> s.videoResolvingPage
    is MediaFetch.Downloading    -> s.videoDownloading
}

/** "12.4 MB / 45.6 MB", or just what has arrived while the size is unknown. */
private fun byteLabel(fetch: MediaFetch): String? {
    val (done, total) = when (fetch) {
        is MediaFetch.Downloading    -> fetch.doneBytes to fetch.totalBytes
        is MediaFetch.InstallingTool -> fetch.doneBytes to fetch.totalBytes
        else                         -> return null
    }
    if (done <= 0L) return null
    return if (total > 0L) "${humanSize(done)} / ${humanSize(total)}" else humanSize(done)
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
