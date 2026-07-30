package hivens.core.net

import java.io.IOException
import java.nio.file.Path

/**
 * One file to fetch. Everything the engine needs to decide how to fetch it, how
 * to resume it, and whether the bytes are the right ones.
 *
 * [size] and [expect] are both optional because the surface really does include
 * transfers with neither: a loader installer jar is chosen at runtime and pinned
 * by nothing but HTTPS to the official maven, and a JDK archive is verified by
 * unpacking it and running `java -version` rather than by hash. Without a size
 * the engine cannot split the transfer into blocks and streams it in one
 * request; without a digest it cannot prove the result, and says so to nobody --
 * that is the caller's contract to keep.
 *
 * [mirrors] are tried in order after [url] has exhausted its retries, not
 * instead of retrying. That ordering matters: a mid-body reset on a flaky route
 * used to burn the fallback host and start from zero, so a transfer that was one
 * retry from finishing began again somewhere else.
 */
data class Transfer(
    val url: String,
    val dest: Path,
    val expect: Digest? = null,
    val size: Long = -1L,
    val mirrors: List<String> = emptyList(),
    /**
     * Sent as `User-Agent`. BellSoft behind CloudFlare blanket-403s the default
     * ktor identifier from whole regions, so that path has to lie about who is
     * calling; everything else keeps its own name.
     */
    val userAgent: String? = null,
    /** Extra request headers, applied to every request this transfer makes. */
    val headers: Map<String, String> = emptyMap(),
    /** When a file is already at [dest], what counts as "already right". */
    val skip: SkipIfPresent = SkipIfPresent.ByDigest,
) {
    /** [url] first, then the fallbacks, deduplicated, in the order they are tried. */
    val sources: List<String> get() = (listOf(url) + mirrors).distinct()
}

/**
 * What makes a file already on disk good enough to leave alone.
 *
 * The choice belongs to the caller because the cost does. Hashing thousands of
 * content-addressed asset objects on every launch spends minutes of disk to
 * answer a question their own paths already answered, while a pack's mods are few
 * and worth the certainty -- a jar can match its recorded size and still be a
 * truncated archive.
 */
enum class SkipIfPresent {
    /** Always refetch. For a destination whose content is not addressed by anything. */
    Never,

    /** Present at the declared size. Cheap, one stat. */
    BySize,

    /**
     * Present at all. For a destination whose path already carries its identity
     * and whose upstream declares no size -- a maven artifact at its coordinate,
     * an object addressed by its own hash. Refetching those on every launch
     * because a manifest omitted a length would be minutes of network for nothing.
     */
    Presence,

    /** Present and hashing to the pinned digest. Falls back to [BySize] with no digest. */
    ByDigest,
}

/** Where a transfer got to, for progress reporting. */
data class TransferProgress(
    val done: Long,
    val total: Long,
    val bytesPerSecond: Long,
    /** Files finished, of how many, when the engine is driving a whole set. */
    val filesDone: Int = 0,
    val filesTotal: Int = 1,
    /** The file being worked on, for a UI that names it. */
    val current: String = "",
)

/** Outcome of one repair pass. */
data class RepairReport(
    val checked: Int,
    val intact: Int,
    /** Files that were wrong and are now right. */
    val repaired: List<String>,
    /** Bytes actually pulled to do it -- the number that says whether block repair earned its keep. */
    val bytesFetched: Long,
    /** Files that were wrong and could not be fixed, with the reason. */
    val failed: Map<String, String>,
)

/**
 * The host refused a resume offset: what is on disk is at least as long as the
 * object, so there is no remainder to send.
 *
 * Distinct from a transport failure because the fix is local -- the partial that
 * decided the offset has to go. A retry that keeps it asks the same
 * unanswerable question and gets the same refusal, on this run and on every
 * later one, which is how a transfer cut after its last byte can wedge a pack
 * install permanently.
 */
class RangeNotSatisfiableException(url: String) :
    IOException("GET $url: resume offset is past the end of the resource")

/** The server answered a ranged request with the whole resource. Not an error, a fact to act on. */
class RangeIgnoredException(url: String) :
    IOException("GET $url: host ignored the range request")

/**
 * A response that was not a success, carrying the code so retry can tell the two
 * kinds apart.
 *
 * A 404 means the object is not there and asking again wastes the user's time --
 * on a manifest full of removed files that was thirteen seconds of backoff each.
 * A 429 or a 5xx means the host is busy or briefly broken, which is exactly what
 * a retry is for. Deciding that from a bare IOException was not possible, so
 * neither case was handled correctly.
 */
class HttpStatusException(val status: Int, url: String, excerpt: String) :
    IOException("GET $url -> HTTP $status $excerpt") {

    /** True for the codes where the same request can plausibly succeed later. */
    val retryable: Boolean
        get() = status == 408 || status == 425 || status == 429 || status in 500..599
}
