package hivens.media

import hivens.core.net.TransferStaging

/**
 * What in the shared `video-cache` directory is a download still in progress.
 *
 * Two writers share the directory and leave different leftovers. The transfer
 * engine keeps a staging file and its journal, which [TransferStaging] names. A
 * yt-dlp fetch of a fragmented source (HLS or DASH) also writes each fragment as
 * `<name>.part-FragN` and an index of them as `<name>.ytdl`. None of those is a
 * video. Taken for one, the cache lookup served a fragment as the finished file
 * for good, and an eviction sweep deleted a journal the next attempt resumes from.
 */
internal object VideoCacheFiles {
    private val YT_DLP_PARTIAL = Regex("""\.part-Frag\d+(\.part)?$|\.ytdl$|\.temp$""")

    /** True when [fileName] belongs to a download that has not finished. */
    fun isPartial(fileName: String): Boolean =
        TransferStaging.isStaging(fileName) || YT_DLP_PARTIAL.containsMatchIn(fileName)

    /**
     * True when [fileName] is the finished file yt-dlp produced for [hash]: the
     * hash, a dot and one extension. Anything with a further suffix is one of the
     * files it writes on the way there.
     */
    fun isFinishedFetch(fileName: String, hash: String): Boolean {
        if (!fileName.startsWith("$hash.") || isPartial(fileName)) return false
        return FINISHED_EXTENSION.matches(fileName.removePrefix("$hash."))
    }

    private val FINISHED_EXTENSION = Regex("""[A-Za-z0-9]{1,5}""")
}
