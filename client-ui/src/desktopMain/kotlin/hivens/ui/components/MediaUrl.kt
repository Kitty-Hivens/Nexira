package hivens.ui.components

import java.net.URI
import java.util.Locale

// Real video containers only. gif/apng/webp are left to the image path (Coil
// animates them) -- this gates which gallery/banner URLs go to the Skinema player.
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "ogv")

// Service pages whose video plays through yt-dlp (download-then-play), not a
// direct file fetch. Matched by host suffix so subdomains (m./music./player.) count.
private val VIDEO_SERVICE_SUFFIXES = listOf("youtube.com", "youtu.be", "vimeo.com", "dailymotion.com")

/** True if [url] points at a video file, judged by its extension (query/fragment stripped). */
fun isVideoUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in VIDEO_EXTENSIONS
}

/** True if [url] is a video-service page (YouTube, Vimeo, ...) playable via yt-dlp. */
fun isVideoServiceUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
    return VIDEO_SERVICE_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

/** Either a direct video file or a service page the player can open in-app. */
fun isPlayableVideoUrl(url: String): Boolean = isVideoUrl(url) || isVideoServiceUrl(url)
