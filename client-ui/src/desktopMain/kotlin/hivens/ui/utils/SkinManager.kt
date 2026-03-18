package hivens.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import hivens.config.AppConfig
import io.ktor.client.*
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

/**
 * Skin manager with persistent disk cache (#61).
 *
 * Cache layout (mirrors original h.java):
 *   ~/.aura/skin-cache/front_<nick>.png
 *   ~/.aura/skin-cache/back_<nick>.png
 *   ~/.aura/skin-cache/raw_<nick>.png   (original texture)
 *
 * Cache is invalidated on explicit call or after [CACHE_TTL_MS].
 *
 * Note: encodeNickname() is used ONLY for URL construction, never for file paths.
 */
class SkinManager(private val httpClient: HttpClient) {

    private companion object {
        private const val BASE_SKIN_URL  = "https://www.smartycraft.ru/skins/"
        private const val BASE_CLOAK_URL = "https://www.smartycraft.ru/cloaks/"
        private const val CACHE_TTL_MS   = 30 * 60 * 1000L // 30 minutes
    }

    private val logger = LoggerFactory.getLogger("SkinManager")

    // In-memory LRU (small, just for the current session)
    private val frontCache = mutableMapOf<String, ImageBitmap>()
    private val backCache  = mutableMapOf<String, ImageBitmap>()

    // Disk cache directory — lazy-initialized
    private val cacheDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, "${AppConfig.APP_DIR}/skin-cache").also { it.mkdirs() }
    }

    // ── Skia rendering settings ────────────────────────────────────────────

    private val samplingNearest = org.jetbrains.skia.FilterMipmap(
        org.jetbrains.skia.FilterMode.NEAREST,
        org.jetbrains.skia.MipmapMode.NONE
    )
    private val samplingLinear = org.jetbrains.skia.FilterMipmap(
        org.jetbrains.skia.FilterMode.LINEAR,
        org.jetbrains.skia.MipmapMode.NONE
    )
    private val paint = org.jetbrains.skia.Paint().apply { isAntiAlias = false }

    // ── Public API ─────────────────────────────────────────────────────────

    fun invalidate(nickname: String) {
        frontCache.remove(nickname)
        backCache.remove(nickname)
        // Delete disk cache
        listOf("front", "back", "raw").forEach { prefix ->
            File(cacheDir, "${prefix}_${nickname}.png").delete()
        }
        logger.info("Cache invalidated for {}", nickname)
    }

    suspend fun getSkinFront(nickname: String): ImageBitmap? = withContext(Dispatchers.IO) {
        // 1. Memory cache
        frontCache[nickname]?.let { return@withContext it }

        // 2. Disk cache
        val diskFile = File(cacheDir, "front_${nickname}.png")
        if (diskFile.exists() && !isExpired(diskFile)) {
            try {
                val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(diskFile.readBytes())
                val result = try {
                    skiaImage.toComposeImageBitmap()
                } finally {
                    skiaImage.close()
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
            val img = org.jetbrains.skia.Image.makeFromBitmap(processed)
            try { img.toComposeImageBitmap() } finally { img.close() }
        }

        // Save to caches
        frontCache[nickname] = result
        saveBitmapToDisk(processed, diskFile)

        return@withContext result
    }

    suspend fun getSkinBack(nickname: String, cloakHash: String? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        backCache[nickname]?.let { return@withContext it }

        // FIX: raw nickname for file path, not encoded — must match invalidate()
        val diskFile = File(cacheDir, "back_${nickname}.png")
        if (diskFile.exists() && !isExpired(diskFile)) {
            try {
                val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(diskFile.readBytes())
                val result = try {
                    skiaImage.toComposeImageBitmap()
                } finally {
                    skiaImage.close()
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
            val img = org.jetbrains.skia.Image.makeFromBitmap(processed)
            try { img.toComposeImageBitmap() } finally { img.close() }
        }

        backCache[nickname] = result
        saveBitmapToDisk(processed, diskFile)

        return@withContext result
    }

    // ── Disk cache helpers ─────────────────────────────────────────────────

    private fun isExpired(file: File): Boolean {
        return System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS
    }

    private suspend fun getOrDownloadRawSkin(nickname: String): org.jetbrains.skia.Image? {
        // raw nickname for file path, encodeNickname only for URL
        val rawFile = File(cacheDir, "raw_${nickname}.png")

        // Try disk cache for raw texture
        if (rawFile.exists() && !isExpired(rawFile)) {
            try {
                return org.jetbrains.skia.Image.makeFromEncoded(rawFile.readBytes())
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
            val image = org.jetbrains.skia.Image.makeFromBitmap(bitmap)
            val data = try {
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            } finally {
                image.close()
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

    private suspend fun downloadTexture(url: String): org.jetbrains.skia.Image? {
        return try {
            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0")
            }
            if (!response.status.isSuccess()) return null
            val bytes = response.bodyAsBytes()
            org.jetbrains.skia.Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            logger.debug("Failed to download texture from {}: {}", url, e.message)
            null
        }
    }

    // ── Skin assembly (ported from original h.java) ────────────────────────

    private fun assembleSkin(
        skin: org.jetbrains.skia.Image,
        isFront: Boolean,
        cloak: org.jetbrains.skia.Image?
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
            val srcRect = org.jetbrains.skia.Rect.makeXYWH(srcX * k, srcY * k, srcW * k, srcH * k)
            val dstRect = org.jetbrains.skia.Rect.makeXYWH(dstX * scale, dstY * scale, dstW * scale, dstH * scale)

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
            val cloakSrc = org.jetbrains.skia.Rect.makeXYWH(1f * kCloak, 1f * kCloak, 10f * kCloak, 16f * kCloak)
            val cloakDst = org.jetbrains.skia.Rect.makeXYWH(3f * scale, 8f * scale, 10f * scale, 16f * scale)
            canvas.drawImageRect(cloak, cloakSrc, cloakDst, cloakSampling, paint, true)
        }

        return output
    }

    // ── URL encoding — only for network requests, never for file paths ─────
    private fun encodeNickname(nickname: String): String =
        URLEncoder.encode(nickname, Charsets.UTF_8.name()).replace("+", "%20")
}
