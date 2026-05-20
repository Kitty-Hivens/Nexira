package hivens.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import hivens.core.api.HttpClientProvider
import hivens.launcher.platform.PlatformPaths
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/**
 * Skin manager with persistent disk cache.
 *
 * Cache layout (mirrors original h.java):
 *   <dataDir>/skin-cache/front_<nick>.png
 *   <dataDir>/skin-cache/back_<nick>.png
 *   <dataDir>/skin-cache/raw_<nick>.png   (original texture)
 *
 * Cache is invalidated on explicit call or after [CACHE_TTL_MS].
 *
 * Two boundary transforms on the nickname:
 *   - encodeNickname() -- URL encoding, used only when building the
 *     skin/cloak HTTP request URL.
 *   - safeCacheBase()  -- filesystem sanitization, used for every disk
 *     cache filename so a nickname with `..`, `/`, `\`, or any reserved
 *     Windows character cannot escape skinCacheDir or write outside it.
 *     Memory-cache keys remain the raw nickname (JVM map; no filesystem
 *     exposure).
 */
class SkinManager(
    private val clientProvider: HttpClientProvider,
    private val paths: PlatformPaths
) {
    private val httpClient get() = clientProvider.current

    private companion object {
        private const val BASE_SKIN_URL  = "https://www.smartycraft.ru/skins/"
        private const val BASE_CLOAK_URL = "https://www.smartycraft.ru/cloaks/"
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
    private val frontCache = lruCache()
    private val backCache  = lruCache()

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

    // ── Skia rendering settings ────────────────────────────────────────────

    private val samplingNearest = FilterMipmap(
        FilterMode.NEAREST,
        MipmapMode.NONE
    )
    private val samplingLinear = FilterMipmap(
        FilterMode.LINEAR,
        MipmapMode.NONE
    )
    private val paint = Paint().apply { isAntiAlias = false }

    // ── Public API ─────────────────────────────────────────────────────────

    fun invalidate(nickname: String) {
        frontCache.remove(nickname)
        backCache.remove(nickname)
        val base = safeCacheBase(nickname)
        // Delete disk cache
        listOf("front", "back", "raw").forEach { prefix ->
            File(cacheDir, "${prefix}_${base}.png").delete()
        }
        logger.info("Cache invalidated for {}", nickname)
    }

    suspend fun getSkinFront(nickname: String): ImageBitmap? = withContext(Dispatchers.IO) {
        // 1. Memory cache
        frontCache[nickname]?.let { return@withContext it }

        // 2. Disk cache
        val diskFile = File(cacheDir, "front_${safeCacheBase(nickname)}.png")
        if (diskFile.exists() && !isExpired(diskFile)) {
            try {
                val skiaImage = Image.makeFromEncoded(diskFile.readBytes())
                val result = skiaImage.use { skiaImage ->
                    skiaImage.toComposeImageBitmap()
                }
                frontCache[nickname] = result
                logger.debug("Front skin loaded from disk cache: {}", nickname)
                return@withContext result
            } catch (e: Exception) {
                logger.warn("Failed to read front cache, re-downloading: {}", e.message)
                diskFile.delete()
            }
        }

        // 3. Download and render
        val rawSkin = getOrDownloadRawSkin(nickname) ?: return@withContext null
        val processed = assembleSkin(rawSkin, isFront = true, cloak = null)
        val result = run {
            val img = Image.makeFromBitmap(processed)
            img.use { img ->
                img.toComposeImageBitmap()
            }
        }

        // Save to caches
        frontCache[nickname] = result
        saveBitmapToDisk(processed, diskFile)

        return@withContext result
    }

    suspend fun getSkinBack(nickname: String, cloakHash: String? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        backCache[nickname]?.let { return@withContext it }

        // Sanitised nickname for file path -- must match invalidate(). Disk
        // path sanitization is independent of URL encoding (see safeCacheBase).
        val diskFile = File(cacheDir, "back_${safeCacheBase(nickname)}.png")
        if (diskFile.exists() && !isExpired(diskFile)) {
            try {
                val skiaImage = Image.makeFromEncoded(diskFile.readBytes())
                val result = skiaImage.use { skiaImage ->
                    skiaImage.toComposeImageBitmap()
                }
                backCache[nickname] = result
                logger.debug("Back skin loaded from disk cache: {}", nickname)
                return@withContext result
            } catch (e: Exception) {
                logger.warn("Failed to read back cache, re-downloading: {}", e.message)
                diskFile.delete()
            }
        }

        val rawSkin = getOrDownloadRawSkin(nickname) ?: return@withContext null

        // encodeNickname used only in URL, not in file path
        val cloakUrl = if (!cloakHash.isNullOrEmpty()) {
            "$BASE_CLOAK_URL$cloakHash.png"
        } else {
            "$BASE_CLOAK_URL${encodeNickname(nickname)}.png"
        }
        val rawCloak = downloadTexture(cloakUrl)

        val processed = assembleSkin(rawSkin, isFront = false, cloak = rawCloak)
        val result = run {
            val img = Image.makeFromBitmap(processed)
            img.use { img ->
                img.toComposeImageBitmap()
            }
        }

        backCache[nickname] = result
        saveBitmapToDisk(processed, diskFile)

        return@withContext result
    }

    // ── Disk cache helpers ─────────────────────────────────────────────────

    private fun isExpired(file: File): Boolean {
        return System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS
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
            rawFile.parentFile?.mkdirs()
            // Re-encode as PNG for caching
            val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            if (data != null) {
                rawFile.writeBytes(data.bytes)
            }
        } catch (e: Exception) {
            logger.warn("Failed to save raw skin to disk: {}", e.message)
        }

        return image
    }

    private fun saveBitmapToDisk(bitmap: org.jetbrains.skia.Bitmap, file: File) {
        try {
            val image = Image.makeFromBitmap(bitmap)
            val data = image.use { image ->
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            }
            if (data != null) {
                file.parentFile?.mkdirs()
                file.writeBytes(data.bytes)
            }
        } catch (e: Exception) {
            logger.warn("Failed to save rendered skin to disk: {}", e.message)
        }
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

    // ── Skin assembly (ported from original h.java) ────────────────────────

    private fun assembleSkin(
        skin: Image,
        isFront: Boolean,
        cloak: Image?
    ): org.jetbrains.skia.Bitmap {
        val viewW = 160
        val viewH = 320

        val output = org.jetbrains.skia.Bitmap()
        output.allocPixels(ImageInfo.makeS32(viewW, viewH, ColorAlphaType.PREMUL))
        val canvas = org.jetbrains.skia.Canvas(output)

        val w = skin.width.toFloat()
        val h = skin.height.toFloat()
        val isHD = w > 64
        val k = w / 64f
        val isLegacy = h == 32f * k
        val armW = 4f
        val scale = 10f
        val samplingMode = if (isHD) samplingLinear else samplingNearest

        fun drawPart(
            srcX: Float, srcY: Float, srcW: Float, srcH: Float,
            dstX: Float, dstY: Float, dstW: Float, dstH: Float,
            mirror: Boolean = false
        ) {
            val srcRect = Rect.makeXYWH(srcX * k, srcY * k, srcW * k, srcH * k)
            val dstRect = Rect.makeXYWH(dstX * scale, dstY * scale, dstW * scale, dstH * scale)

            if (mirror) {
                canvas.save()
                val centerX = dstRect.left + dstRect.width / 2
                canvas.translate(centerX, 0f)
                canvas.scale(-1f, 1f)
                canvas.translate(-centerX, 0f)
                canvas.drawImageRect(skin, srcRect, dstRect, samplingMode, paint, true)
                canvas.restore()
            } else {
                canvas.drawImageRect(skin, srcRect, dstRect, samplingMode, paint, true)
            }
        }

        // Head
        val headSrcX = if (isFront) 8f else 24f
        drawPart(headSrcX, 8f, 8f, 8f, 4f, 0f, 8f, 8f)
        val helmSrcX = if (isFront) 40f else 56f
        drawPart(helmSrcX, 8f, 8f, 8f, 4f, 0f, 8f, 8f)

        // Body
        val bodySrcX = if (isFront) 20f else 32f
        drawPart(bodySrcX, 20f, 8f, 12f, 4f, 8f, 8f, 12f)
        if (!isLegacy) {
            val body2SrcX = if (isFront) 20f else 32f
            drawPart(body2SrcX, 36f, 8f, 12f, 4f, 8f, 8f, 12f)
        }

        // Arms
        if (isFront) {
            drawPart(44f, 20f, armW, 12f, 4f - armW, 8f, armW, 12f)
            val dstXLeft = 12f
            if (isLegacy) {
                drawPart(44f, 20f, armW, 12f, dstXLeft, 8f, armW, 12f, mirror = true)
            } else {
                drawPart(36f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f)
                drawPart(52f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f)
                drawPart(44f, 36f, armW, 12f, 4f - armW, 8f, armW, 12f)
            }
        } else {
            val dstXRight = 12f
            val dstXLeft = 4f - armW
            drawPart(52f, 20f, armW, 12f, dstXRight, 8f, armW, 12f)
            if (!isLegacy) drawPart(52f, 36f, armW, 12f, dstXRight, 8f, armW, 12f)
            if (isLegacy) {
                drawPart(52f, 20f, armW, 12f, dstXLeft, 8f, armW, 12f, mirror = true)
            } else {
                drawPart(44f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f)
                drawPart(60f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f)
            }
        }

        // Legs
        if (isFront) {
            drawPart(4f, 20f, 4f, 12f, 4f, 20f, 4f, 12f)
            if (!isLegacy) drawPart(4f, 36f, 4f, 12f, 4f, 20f, 4f, 12f)
        } else {
            drawPart(12f, 20f, 4f, 12f, 8f, 20f, 4f, 12f)
            if (!isLegacy) drawPart(12f, 36f, 4f, 12f, 8f, 20f, 4f, 12f)
        }
        if (isLegacy) {
            if (isFront) drawPart(4f, 20f, 4f, 12f, 8f, 20f, 4f, 12f, mirror = true)
            else drawPart(12f, 20f, 4f, 12f, 4f, 20f, 4f, 12f, mirror = true)
        } else {
            if (isFront) {
                drawPart(20f, 52f, 4f, 12f, 8f, 20f, 4f, 12f)
                drawPart(4f, 52f, 4f, 12f, 8f, 20f, 4f, 12f)
            } else {
                drawPart(28f, 52f, 4f, 12f, 4f, 20f, 4f, 12f)
                drawPart(12f, 52f, 4f, 12f, 4f, 20f, 4f, 12f)
            }
        }

        // Cloak (back view only)
        if (!isFront && cloak != null) {
            val kCloak = cloak.width.toFloat() / 64f
            val isCloakHD = cloak.width > 64
            val cloakSampling = if (isCloakHD) samplingLinear else samplingNearest
            val cloakSrc = Rect.makeXYWH(1f * kCloak, 1f * kCloak, 10f * kCloak, 16f * kCloak)
            val cloakDst = Rect.makeXYWH(3f * scale, 8f * scale, 10f * scale, 16f * scale)
            canvas.drawImageRect(cloak, cloakSrc, cloakDst, cloakSampling, paint, true)
        }

        return output
    }

    // ── URL encoding -- only for network requests, never for file paths ─────
    private fun encodeNickname(nickname: String): String =
        URLEncoder.encode(nickname, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * Maps a nickname to a filesystem-safe base name for the disk cache.
     * Anything outside `[A-Za-z0-9_-]` becomes `_`; the result is capped
     * at 64 chars so we don't blow past PATH_MAX on adversarial input.
     *
     * Used for every `File(cacheDir, "${prefix}_${...}.png")` call. Keeps
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
