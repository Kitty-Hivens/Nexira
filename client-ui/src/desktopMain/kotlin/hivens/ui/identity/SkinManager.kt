package hivens.ui.identity

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import hivens.core.api.HttpClientProvider
import hivens.core.io.AtomicFiles
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import hivens.launcher.platform.PlatformPaths
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Skin manager with persistent disk cache. Serves the raw skin texture for the
 * 3D renderer ([hivens.ui.skin3d.SkinView3D]); the old 2D front/back bake is
 * gone -- projection happens live at draw time, so only the original texture is
 * fetched + cached.
 *
 * Cache layout:
 *   <dataDir>/skin-cache/raw_<nick>.png   (original texture)
 *
 * Cache is invalidated on explicit call or after [CACHE_TTL_MS].
 *
 * Two boundary transforms on the nickname:
 *   - encodeNickname() -- URL encoding, used only when building the
 *     skin HTTP request URL.
 *   - safeCacheBase()  -- filesystem sanitization, used for every disk
 *     cache filename so a nickname with `..`, `/`, `\`, or any reserved
 *     Windows character cannot escape skinCacheDir or write outside it.
 *     Memory-cache keys remain the raw nickname (JVM map; no filesystem
 *     exposure).
 */
class SkinManager(
    private val clientProvider: HttpClientProvider,
    private val paths: PlatformPaths,
    private val clock: Clock = SystemClock,
) {
    private val httpClient get() = clientProvider.current

    private companion object {
        private const val BASE_SKIN_URL  = "https://www.smartycraft.ru/skins/"
        private const val CACHE_TTL_MS   = 30 * 60 * 1000L // 30 minutes
        /** Hard cap on per-cache entries to bound GPU texture memory. */
        private const val SKIN_CACHE_MAX = 64
    }

    private val logger = LoggerFactory.getLogger("SkinManager")

    // In-memory LRU (small, just for the current session). Bounded --
    // each ImageBitmap holds GPU texture memory and an unbounded session
    // cache (server admins switching between dozens of alt accounts) led
    // to OOM crashes during long sessions. Cap at SKIN_CACHE_MAX entries
    // per cache; eldest gets evicted on overflow. Access-order (3rd ctor
    // arg = true) so freshly-viewed skins survive evictions.
    private val rawCache = lruCache()

    private fun lruCache(): MutableMap<String, ImageBitmap> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, ImageBitmap>(SKIN_CACHE_MAX, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                    size > SKIN_CACHE_MAX
            },
        )

    // Disk cache directory -- lazy-initialized
    private val cacheDir: File by lazy {
        paths.skinCacheDir.toFile().also { it.mkdirs() }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun invalidate(nickname: String) {
        rawCache.remove(nickname)
        File(cacheDir, "raw_${safeCacheBase(nickname)}.png").delete()
        logger.info("Cache invalidated for {}", nickname)
    }

    /**
     * The raw skin texture (64x64 modern, 64x32 legacy, or HD multiples) as a
     * Compose [ImageBitmap]. The 3D renderer ([hivens.ui.skin3d.SkinView3D])
     * projects this live, so SkinManager no longer bakes a fixed 2D view.
     * Memory + disk cached (raw_<nick>.png) via [getOrDownloadRawSkin]; null
     * when the texture cannot be fetched.
     */
    suspend fun getRawSkin(nickname: String): ImageBitmap? = withContext(Dispatchers.IO) {
        rawCache[nickname]?.let { return@withContext it }
        val raw = getOrDownloadRawSkin(nickname) ?: return@withContext null
        val bitmap = raw.use { it.toComposeImageBitmap() }
        rawCache[nickname] = bitmap
        bitmap
    }

    /**
     * The current skin's raw PNG bytes for [nickname] -- the exact cached texture
     * the 3D renderer draws, so callers (the wardrobe auto-importing the current
     * look) can persist it. Ensures the texture is fetched/cached, then returns the
     * cache file's bytes; null when it cannot be fetched.
     */
    suspend fun getRawSkinBytes(nickname: String): ByteArray? = withContext(Dispatchers.IO) {
        (getOrDownloadRawSkin(nickname) ?: return@withContext null).use { }
        val rawFile = File(cacheDir, "raw_${safeCacheBase(nickname)}.png")
        runCatching { rawFile.readBytes() }.getOrNull()
    }

    // ── Disk cache helpers ─────────────────────────────────────────────────

    internal fun isExpired(file: File): Boolean {
        return clock.nowMillis() - file.lastModified() > CACHE_TTL_MS
    }

    private suspend fun getOrDownloadRawSkin(nickname: String): Image? {
        // safeCacheBase for file path, encodeNickname only for URL
        val rawFile = File(cacheDir, "raw_${safeCacheBase(nickname)}.png")

        // Try disk cache for raw texture
        if (rawFile.exists() && !isExpired(rawFile)) {
            try {
                return Image.makeFromEncoded(rawFile.readBytes())
            } catch (_: Exception) {
                rawFile.delete()
            }
        }

        // Download
        val image = downloadTexture("$BASE_SKIN_URL${encodeNickname(nickname)}.png") ?: return null

        // Save raw to disk
        try {
            // Re-encode as PNG for caching
            image.encodeToData(EncodedImageFormat.PNG)?.use { encoded ->
                AtomicFiles.writeBytes(rawFile.toPath(), encoded.bytes)
            }
        } catch (e: Exception) {
            logger.warn("Failed to save raw skin to disk: {}", e.message)
        }

        return image
    }

    // ── Network ────────────────────────────────────────────────────────────

    private suspend fun downloadTexture(url: String): Image? {
        return try {
            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0")
            }
            if (!response.status.isSuccess()) return null
            val bytes = response.bodyAsBytes()
            Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            logger.debug("Failed to download texture from {}: {}", url, e.message)
            null
        }
    }

    // ── URL encoding -- only for network requests, never for file paths ─────
    private fun encodeNickname(nickname: String): String =
        URLEncoder.encode(nickname, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * Maps a nickname to a filesystem-safe base name for the disk cache.
     * Anything outside `[A-Za-z0-9_-]` becomes `_`; the result is capped
     * at 64 chars so we don't blow past PATH_MAX on adversarial input.
     *
     * Used for the `File(cacheDir, "raw_${...}.png")` cache file. Keeps
     * the cache directory closed under path-traversal: `..`, `/`, `\`,
     * NUL, and Windows reserved characters all collapse to `_` before
     * the path is composed, so an attacker who can dictate the nickname
     * cannot write to or read from a location outside skinCacheDir.
     *
     * Deterministic and idempotent so subsequent `invalidate()` and
     * cache lookups for the same nickname find the same file. Collisions
     * between two raw nicknames mapping to the same base are theoretical
     * (SC nicknames are ASCII-letters/digits/underscore in practice) and
     * would only manifest as one user briefly seeing the other's cached
     * skin until the TTL expires -- a strictly smaller blast radius than
     * the path-traversal risk it eliminates.
     */
    private fun safeCacheBase(nickname: String): String {
        val cleaned = nickname.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return if (cleaned.length > 64) cleaned.take(64) else cleaned
    }
}
