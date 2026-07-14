package hivens.media

import hivens.core.api.HttpClientProvider
import hivens.core.platform.Arch
import hivens.core.platform.OS
import hivens.core.platform.Platform
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    private val http: HttpClientProvider,
    private val scope: CoroutineScope,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    private val log = LoggerFactory.getLogger(YtDlpService::class.java)
    private val inflight = ConcurrentHashMap<String, Deferred<Path>>()
    private val binaryMutex = Mutex()

    @Volatile
    private var binaryCmd: String? = null

    /** The local file for [pageUrl], downloading via yt-dlp first if absent. Throws on failure. */
    suspend fun resolve(pageUrl: String): Path {
        val hash = hash(pageUrl)
        existingCached(hash)?.let { return it }
        val deferred = inflight.computeIfAbsent(pageUrl) {
            scope.async(Dispatchers.IO) {
                try {
                    download(pageUrl, hash)
                } finally {
                    inflight.remove(pageUrl)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun download(pageUrl: String, hash: String): Path {
        existingCached(hash)?.let { return it }
        val ytdlp = ensureBinary()
        Files.createDirectories(videoCacheDir)
        // %(ext)s lets yt-dlp pick the container; we find the result by the hash prefix.
        val outTemplate = videoCacheDir.resolve("$hash.%(ext)s").toString()
        val cmd = listOf(
            ytdlp,
            "-f", FORMAT,
            "--no-playlist",
            "--no-part",
            "--no-progress",
            "-o", outTemplate,
            pageUrl,
        )
        runProcess(cmd, DOWNLOAD_TIMEOUT_SECONDS)
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

    private suspend fun ensureBinary(): String {
        binaryCmd?.let { return it }
        return binaryMutex.withLock {
            binaryCmd?.let { return@withLock it }
            val resolved = locateOrInstall()
            binaryCmd = resolved
            resolved
        }
    }

    private suspend fun locateOrInstall(): String {
        if (probeVersion("yt-dlp")) return "yt-dlp"
        val asset = ytDlpAsset(OS.platform, OS.arch)
            ?: throw IOException("No yt-dlp build for ${OS.platform}/${OS.arch}")
        val local = toolsDir.resolve(asset)
        if (Files.isRegularFile(local) && probeVersion(local.toString())) return local.toString()
        downloadBinary(asset, local)
        if (!OS.isWindows) setExecutable(local)
        if (!probeVersion(local.toString())) {
            throw IOException("Downloaded yt-dlp failed --version; archive may be corrupt")
        }
        log.info("yt-dlp installed at {}", local)
        return local.toString()
    }

    private suspend fun downloadBinary(asset: String, target: Path) {
        Files.createDirectories(toolsDir)
        val url = "$RELEASE_BASE/$asset"
        val part = target.resolveSibling("${target.fileName}.part")
        try {
            http.current.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) throw IOException("GET $url -> ${resp.status}")
                FileOutputStream(part.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
            }
            if (runCatching { Files.size(part) }.getOrDefault(0L) <= 0L) throw IOException("Empty yt-dlp download")
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            throw e
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

    private fun runProcess(cmd: List<String>, timeoutSeconds: Long) {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val tail = StringBuilder()
        // Drain stdout in a daemon thread so a full pipe buffer cannot wedge the
        // process; keep the tail for an error message.
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) {
                        tail.appendLine(line)
                        if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
            throw IOException("yt-dlp timed out after ${timeoutSeconds}s")
        }
        reader.join(2_000)
        val code = proc.exitValue()
        if (code != 0) {
            val msg = synchronized(tail) { tail.toString().trim() }
            throw IOException("yt-dlp exited $code: ${msg.takeLast(400)}")
        }
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

    private companion object {
        const val RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        // Single progressive file (audio+video muxed) so no ffmpeg merge is
        // needed; cap at 720p to keep the whole-file download reasonable.
        const val FORMAT = "best[height<=720][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
        const val DOWNLOAD_TIMEOUT_SECONDS = 600L
        const val VERSION_TIMEOUT_SECONDS = 5L
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
