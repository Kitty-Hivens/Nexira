package hivens.media

import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.platform.Arch
import hivens.core.platform.OS
import hivens.core.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Plays video from a service page (YouTube, Vimeo, ...) by running yt-dlp to
 * download a single progressive file into the video cache, which the local-only
 * Skinema player then opens. Streaming is impossible (Skinema does no network),
 * so the file is fetched whole before playback.
 *
 * The yt-dlp binary is located on PATH first, else downloaded on demand from the
 * official GitHub releases into `<dataDir>/tools` and cached (the same
 * locate/executable/verify pattern the JRE provisioning uses). One install at a
 * time ([binaryMutex]); one download per page URL ([inflight], like
 * [VideoCacheService]).
 */
class YtDlpService(
    private val toolsDir: Path,
    private val videoCacheDir: Path,
    private val transfers: TransferEngine,
    private val scope: CoroutineScope,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) : MediaResolver {
    private val log = LoggerFactory.getLogger(YtDlpService::class.java)
    private val inflight = ConcurrentHashMap<String, Deferred<Path>>()
    private val binaryMutex = Mutex()

    // url -> what that fetch is doing. Kept for the process lifetime, like the
    // cache service's: a viewer holds the flow it was handed.
    private val fetches = ConcurrentHashMap<String, MutableStateFlow<MediaFetch>>()

    @Volatile
    private var binaryCmd: String? = null

    /**
     * What the fetch for [url] is doing, for a viewer to render. Reports the
     * tool install and the page resolve as themselves rather than as one long
     * unexplained wait.
     */
    override fun fetchState(url: String): StateFlow<MediaFetch> = stateOf(url).asStateFlow()

    /**
     * Stops the download for [url], killing the yt-dlp process with it -- a
     * cancelled coroutine leaves a subprocess running otherwise, which is the
     * whole cost the user asked to stop paying.
     */
    override fun cancel(url: String) {
        inflight[url]?.cancel()
    }

    private fun stateOf(url: String): MutableStateFlow<MediaFetch> =
        fetches.computeIfAbsent(url) { MutableStateFlow(MediaFetch.Idle) }

    /** The local file for [url], downloading via yt-dlp first if absent. Throws on failure. */
    override suspend fun resolve(url: String): Path {
        val hash = hash(url)
        existingCached(hash)?.let { return it }
        val deferred = inflight.computeIfAbsent(url) {
            scope.async(Dispatchers.IO) {
                try {
                    download(url, hash)
                } finally {
                    inflight.remove(url)
                    stateOf(url).value = MediaFetch.Idle
                }
            }
        }
        return deferred.await()
    }

    private suspend fun download(pageUrl: String, hash: String): Path {
        existingCached(hash)?.let { return it }
        val progress = stateOf(pageUrl)
        progress.value = MediaFetch.InstallingTool()
        val ytdlp = ensureBinary(progress)
        Files.createDirectories(videoCacheDir)
        // %(ext)s lets yt-dlp pick the container; we find the result by the hash prefix.
        val outTemplate = videoCacheDir.resolve("$hash.%(ext)s").toString()
        val cmd = downloadArgs(ytdlp, outTemplate, pageUrl)
        progress.value = MediaFetch.Resolving
        runProcess(cmd, DOWNLOAD_TIMEOUT_SECONDS) { line ->
            parseYtDlpProgress(line)?.let { progress.value = it }
        }
        val file = existingCached(hash) ?: throw IOException("yt-dlp produced no playable file for $pageUrl")
        evictOverCap()
        return file
    }

    private fun existingCached(hash: String): Path? {
        if (!Files.isDirectory(videoCacheDir)) return null
        val match = Files.list(videoCacheDir).use { s ->
            s.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().startsWith("$hash.") &&
                    !it.fileName.toString().endsWith(".part")
            }.findFirst().orElse(null)
        } ?: return null
        return match.takeIf { runCatching { Files.size(it) > 0L }.getOrDefault(false) }
    }

    // -- yt-dlp binary -------------------------------------------------------

    private suspend fun ensureBinary(progress: MutableStateFlow<MediaFetch>): String {
        binaryCmd?.let { return it }
        return binaryMutex.withLock {
            binaryCmd?.let { return@withLock it }
            val resolved = locateOrInstall(progress)
            binaryCmd = resolved
            resolved
        }
    }

    private suspend fun locateOrInstall(progress: MutableStateFlow<MediaFetch>): String {
        if (probeVersion("yt-dlp")) return "yt-dlp"
        val asset = ytDlpAsset(OS.platform, OS.arch)
            ?: throw IOException("No yt-dlp build for ${OS.platform}/${OS.arch}")
        val local = toolsDir.resolve(asset)
        if (Files.isRegularFile(local) && probeVersion(local.toString())) return local.toString()
        downloadBinary(asset, local, progress)
        if (!OS.isWindows) setExecutable(local)
        if (!probeVersion(local.toString())) {
            throw IOException("Downloaded yt-dlp failed --version; archive may be corrupt")
        }
        log.info("yt-dlp installed at {}", local)
        return local.toString()
    }

    /**
     * The tool binary from its release page. Nothing upstream publishes a hash we
     * read, so the check is the same one the install path already makes: the binary
     * has to answer `--version` before it is used.
     */
    private suspend fun downloadBinary(asset: String, target: Path, progress: MutableStateFlow<MediaFetch>) {
        transfers.fetch(Transfer(url = "$RELEASE_BASE/$asset", dest = target, skip = SkipIfPresent.Never)) { done, total ->
            progress.value = MediaFetch.InstallingTool(done, total)
        }
        if (runCatching { Files.size(target) }.getOrDefault(0L) <= 0L) {
            Files.deleteIfExists(target)
            throw IOException("Empty yt-dlp download")
        }
    }

    private fun setExecutable(path: Path) {
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Windows: no POSIX bits to set.
        } catch (e: Exception) {
            log.warn("Failed to set executable bit on {}", path, e)
        }
    }

    private fun probeVersion(cmd: String): Boolean = runCatching {
        val proc = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
        Thread { runCatching { proc.inputStream.use { it.readBytes() } } }.apply { isDaemon = true }.start()
        if (!proc.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly(); proc.waitFor(3, TimeUnit.SECONDS); return false
        }
        proc.exitValue() == 0
    }.getOrDefault(false)

    // -- process -------------------------------------------------------------

    /**
     * Runs [cmd] to completion, feeding every output line to [onLine].
     *
     * Cancellable, and killing the process is the point of that: a cancelled
     * coroutine used to return while yt-dlp carried on downloading, which is the
     * exact cost the user asked to stop paying. The wait is therefore a poll --
     * a bare blocking waitFor cannot notice a cancellation at all -- at a period
     * short enough to feel immediate and long enough to cost nothing.
     */
    private suspend fun runProcess(cmd: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit) {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val tail = StringBuilder()
        // Drain stdout in a daemon thread so a full pipe buffer cannot wedge the
        // process; keep the tail for an error message.
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    onLine(line)
                    synchronized(tail) {
                        tail.appendLine(line)
                        if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (proc.isAlive) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadlineNanos) {
                    kill(proc)
                    throw IOException("yt-dlp timed out after ${timeoutSeconds}s")
                }
                proc.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)
            }
        } catch (e: CancellationException) {
            kill(proc)
            throw e
        }
        reader.join(2_000)
        val code = proc.exitValue()
        if (code != 0) {
            val msg = synchronized(tail) { tail.toString().trim() }
            throw IOException("yt-dlp exited $code: ${msg.takeLast(400)}")
        }
    }

    private fun kill(proc: Process) {
        proc.destroyForcibly()
        proc.waitFor(5, TimeUnit.SECONDS)
    }

    // -- cache eviction (shared video-cache dir) -----------------------------

    private fun evictOverCap() {
        runCatching {
            val files = Files.list(videoCacheDir).use { s ->
                s.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.toList()
            }
            var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
            if (total <= maxCacheBytes) return
            files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .forEach { f ->
                    if (total <= maxCacheBytes) return
                    val size = runCatching { Files.size(f) }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) total -= size
                }
        }
    }

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    internal companion object {
        /**
         * The yt-dlp invocation for one page fetch. Pure, so the one argument that
         * must never come back can be held to it by a test.
         *
         * `--no-part` is that argument. With it, yt-dlp writes straight to the
         * finished filename, and this service kills the process on cancel and on
         * timeout -- so a stopped fetch left a truncated video sitting under the
         * name of a complete one. Nothing downstream could tell: the cache lookup
         * accepts any non-`.part` file of non-zero size, so every later request for
         * that URL returned the short file and never downloaded again. Both the
         * lookup and the eviction sweep already filter `.part`; the flag was what
         * made those filters match nothing.
         *
         * Without it yt-dlp writes `<name>.part` and renames only when the file is
         * whole, and a leftover part-file is resumed by the next attempt.
         */
        internal fun downloadArgs(ytdlp: String, outTemplate: String, pageUrl: String): List<String> = listOf(
            ytdlp,
            "-f", FORMAT,
            "--no-playlist",
            // Counters rather than silence: --no-progress left the caller with
            // nothing to show for a download that runs for minutes. One line per
            // update (the default rewrites a single line with a carriage return,
            // which never reaches a line reader) in a template we can parse.
            "--newline",
            "--progress-template", "download:$YT_DLP_PROGRESS_MARKER " +
                "%(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s",
            "-o", outTemplate,
            pageUrl,
        )

        const val RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        // Single progressive file (audio+video muxed) so no ffmpeg merge is
        // needed; cap at 720p to keep the whole-file download reasonable.
        const val FORMAT = "best[height<=720][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
        const val DOWNLOAD_TIMEOUT_SECONDS = 600L
        const val VERSION_TIMEOUT_SECONDS = 5L
        // How often the run loop looks up from the process: the delay a cancel
        // takes to land, and 300 wakeups over a ten-minute download.
        const val POLL_MILLIS = 200L
        const val MAX_TAIL = 4_000
        const val DEFAULT_MAX_CACHE_BYTES = 600L * 1024 * 1024
    }
}

/** The yt-dlp release asset name for a platform/arch, or null if unsupported. */
internal fun ytDlpAsset(platform: Platform, arch: Arch): String? = when (platform) {
    Platform.WINDOWS -> "yt-dlp.exe"
    Platform.MACOS -> "yt-dlp_macos"
    Platform.LINUX -> if (arch == Arch.ARM64) "yt-dlp_linux_aarch64" else "yt-dlp_linux"
    Platform.UNKNOWN -> null
}
