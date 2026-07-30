package hivens.media

import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/**
 * Resolves a remote video URL to a local file under `<dataDir>/video-cache`.
 * Skinema plays local files only (built `--disable-network`), so a URL video
 * must land on disk before the player opens it.
 *
 * One download per URL at a time (single-flight). The download runs in [scope],
 * NOT the caller's coroutine, so a viewer leaving the composition does not abort
 * a fetch the next visit (or a concurrent viewer) will want. The cache is
 * size-capped, evicting least-recently-touched files past [maxBytes].
 */
class VideoCacheService(
    private val dir: Path,
    private val transfers: TransferEngine,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val log = LoggerFactory.getLogger(VideoCacheService::class.java)

    // url -> in-flight download. Keyed by url so concurrent resolves of the same
    // video share one download; entry removed when it settles.
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<Path>>()

    /** The local file for [url], downloading it first if absent. Throws on failure. */
    suspend fun resolve(url: String): Path {
        val target = dir.resolve(cacheKey(url))
        if (isUsable(target)) {
            touch(target)
            return target
        }
        val deferred = inflight.computeIfAbsent(url) {
            scope.async(Dispatchers.IO) {
                try {
                    download(url, target)
                    target
                } finally {
                    inflight.remove(url)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun download(url: String, target: Path) {
        if (isUsable(target)) return
        Files.createDirectories(dir)
        try {
            // A video is the one thing here that is reliably large, and the cache key
            // is a hash of the url -- so the destination is stable, its partial can be
            // continued across attempts and across runs, and the transfer runs in
            // blocks rather than as one stream that a wobble has to restart.
            transfers.fetch(Transfer(url = url, dest = target, skip = SkipIfPresent.Never))
            if (runCatching { Files.size(target) }.getOrDefault(0L) <= 0L) {
                Files.deleteIfExists(target)
                throw IOException("Empty video body from $url")
            }
            evictOverCap()
        } catch (e: Exception) {
            log.warn("Video download failed for {}", url, e)
            throw e
        }
    }

    private fun isUsable(target: Path): Boolean =
        Files.isRegularFile(target) && runCatching { Files.size(target) > 0L }.getOrDefault(false)

    // LRU touch -- eviction picks the oldest mtime, so a cache hit refreshes it.
    private fun touch(target: Path) {
        runCatching { Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis())) }
    }

    private fun evictOverCap() {
        runCatching {
            val files = Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.toList()
            }
            var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
            if (total <= maxBytes) return
            files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .forEach { f ->
                    if (total <= maxBytes) return
                    val size = runCatching { Files.size(f) }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) {
                        total -= size
                        log.info("Evicted cached video {} ({} bytes) to stay under cap", f.fileName, size)
                    }
                }
        }
    }

    // SHA-256 of the URL keeps the filename filesystem-safe and bounded; the
    // original extension is preserved where sane (cosmetic -- FFmpeg sniffs by content).
    private fun cacheKey(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) "$hash.$ext" else hash
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 600L * 1024 * 1024
    }
}
