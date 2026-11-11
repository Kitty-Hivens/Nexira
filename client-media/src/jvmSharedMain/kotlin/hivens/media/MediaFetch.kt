package hivens.media

/**
 * What resolving a media URL is doing right now, so a viewer can say it rather
 * than spin. The distinction between the phases is the whole point: on a first
 * run the wait is the downloader being installed, on a service page it is the
 * page being resolved, and only then is it the video itself arriving -- three
 * waits that looked identical from the outside.
 */
sealed interface MediaFetch {

    /**
     * How far this phase has got, 0..1, or null when it has no size to measure
     * against -- which a renderer shows as an indeterminate measure rather than
     * as zero percent.
     */
    val fraction: Float? get() = null

    /** Nothing is running for this URL: never started, or already settled. */
    data object Idle : MediaFetch

    /** Fetching yt-dlp itself, which only happens on the first service URL. */
    data class InstallingTool(val doneBytes: Long = 0L, val totalBytes: Long = 0L) : MediaFetch {
        override val fraction: Float? get() = measure(doneBytes, totalBytes)
    }

    /** yt-dlp is reading the page and picking a format; no bytes yet. */
    data object Resolving : MediaFetch

    /**
     * The file is coming down. [totalBytes] is zero while the size is unknown --
     * a server that sends no length, or an estimate yt-dlp has not settled.
     */
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : MediaFetch {
        override val fraction: Float? get() = measure(doneBytes, totalBytes)
    }
}

private fun measure(done: Long, total: Long): Float? =
    if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else null

/**
 * The marker [YtDlpService] asks yt-dlp to print its progress behind, so the
 * counters can be told apart from every other line the tool writes.
 */
internal const val YT_DLP_PROGRESS_MARKER = "nx-progress"

/**
 * Reads one yt-dlp progress line into a [MediaFetch.Downloading], or null for
 * any other output.
 *
 * The tool prints `NA` for a field it does not know and a float for an estimate,
 * so the total falls back from the real size to the estimate to unknown, and
 * every number is parsed as a decimal before being truncated. Pure, so the
 * format is pinned without running the tool.
 */
internal fun parseYtDlpProgress(line: String): MediaFetch.Downloading? {
    if (!line.contains(YT_DLP_PROGRESS_MARKER)) return null
    val fields = line.substringAfter(YT_DLP_PROGRESS_MARKER).trim().split(' ').filter { it.isNotEmpty() }
    val done = number(fields.getOrNull(0)) ?: return null
    val total = number(fields.getOrNull(1)) ?: number(fields.getOrNull(2)) ?: 0L
    return MediaFetch.Downloading(done, total.coerceAtLeast(0L))
}

private fun number(field: String?): Long? = field?.toDoubleOrNull()?.toLong()
