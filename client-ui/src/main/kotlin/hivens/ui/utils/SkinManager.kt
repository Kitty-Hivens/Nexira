package hivens.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import hivens.config.ServiceEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

object SkinManager {
    private const val OUTPUT_SCALE = 8
    private val cacheBusters = ConcurrentHashMap<String, Long>()
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()

    fun invalidate(username: String) {
        cacheBusters[username] = System.currentTimeMillis()
        memoryCache.remove(username)
        memoryCache.remove("${username}_front")
        memoryCache.remove("${username}_back")
    }

    suspend fun getSkinFront(username: String): ImageBitmap? = withContext(Dispatchers.IO) {
        getOrLoadAssembled(username, false)
    }

    suspend fun getSkinBack(username: String): ImageBitmap? = withContext(Dispatchers.IO) {
        getOrLoadAssembled(username, true)
    }

    private fun getOrLoadAssembled(username: String, backView: Boolean): ImageBitmap? {
        val cacheKey = "${username}_${if (backView) "back" else "front"}"
        if (memoryCache.containsKey(cacheKey)) return memoryCache[cacheKey]

        val rawImage = downloadSkin(username) ?: return null
        val assembled = assemble(rawImage, backView) ?: return null

        val composeBitmap = assembled.toComposeImageBitmap()
        memoryCache[cacheKey] = composeBitmap
        return composeBitmap
    }

    private fun downloadSkin(username: String): BufferedImage? {
        return try {
            var urlStr = "${ServiceEndpoints.BASE_URL}/skins/$username.png"
            if (cacheBusters.containsKey(username)) {
                urlStr += "?t=${cacheBusters[username]}"
            }
            val url = URL(urlStr)
            ImageIO.read(url)
        } catch (e: Exception) {
            null
        }
    }

    // Логика смешной нарезки скинь а
    private fun assemble(skin: BufferedImage, backView: Boolean): BufferedImage? {
        val w = skin.width
        val h = skin.height
        if (w == 0 || h == 0) return null

        val is64x64 = (h == w)
        val finalW = 16 * OUTPUT_SCALE
        val finalH = 32 * OUTPUT_SCALE

        val dest = BufferedImage(finalW, finalH, BufferedImage.TYPE_INT_ARGB)
        val g = dest.graphics

        // Вспомогательная функция для рисования части тела
        fun drawLimb(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, flipX: Boolean) {
            val ratio = w / 64.0
            val actualSX = (sx * ratio).toInt()
            val actualSY = (sy * ratio).toInt()
            val actualSW = (sw * ratio).toInt()
            val actualSH = (sh * ratio).toInt()

            // Вырезаем кусок
            var part = skin.getSubimage(actualSX, actualSY, actualSW, actualSH)

            // Масштабируем до целевого размера
            val destW = sw * OUTPUT_SCALE
            val destH = sh * OUTPUT_SCALE

            // Если нужно отразить (Flip X)
            if (flipX) {
                val tx = java.awt.geom.AffineTransform.getScaleInstance(-1.0, 1.0)
                tx.translate(-part.width.toDouble(), 0.0)
                val op = java.awt.image.AffineTransformOp(tx, java.awt.image.AffineTransformOp.TYPE_NEAREST_NEIGHBOR)
                part = op.filter(part, null)
            }

            g.drawImage(part, dx * OUTPUT_SCALE, dy * OUTPUT_SCALE, destW, destH, null)
        }

        if (!backView) {
            drawLimb(8, 8, 8, 8, 4, 0, false)   // Head
            drawLimb(40, 8, 8, 8, 4, 0, false)  // Hat (Accessory)
            drawLimb(20, 20, 8, 12, 4, 8, false) // Body
            if (is64x64) drawLimb(20, 36, 8, 12, 4, 8, false) // Body Layer 2

            drawLimb(44, 20, 4, 12, 0, 8, false) // Right Arm
            if (is64x64) drawLimb(44, 36, 4, 12, 0, 8, false)

            // Left Arm
            if (is64x64) {
                drawLimb(36, 52, 4, 12, 12, 8, false)
                drawLimb(52, 52, 4, 12, 12, 8, false)
            } else {
                drawLimb(44, 20, 4, 12, 12, 8, true) // Flip for 1.7 skins
            }

            drawLimb(4, 20, 4, 12, 4, 20, false) // Right Leg
            if (is64x64) drawLimb(4, 36, 4, 12, 4, 20, false)

            // Left Leg
            if (is64x64) {
                drawLimb(20, 52, 4, 12, 8, 20, false)
                drawLimb(4, 52, 4, 12, 8, 20, false)
            } else {
                drawLimb(4, 20, 4, 12, 8, 20, true)
            }
        } else {
            // BACK VIEW
            drawLimb(24, 8, 8, 8, 4, 0, false)   // Head Back
            drawLimb(56, 8, 8, 8, 4, 0, false)   // Hat Back

            drawLimb(32, 20, 8, 12, 4, 8, false) // Body Back
            if (is64x64) drawLimb(32, 36, 8, 12, 4, 8, false)

            drawLimb(52, 20, 4, 12, 12, 8, false) // Right Arm Back
            if (is64x64) drawLimb(52, 36, 4, 12, 12, 8, false)

            if (is64x64) {
                drawLimb(44, 52, 4, 12, 0, 8, false) // Left Arm Back
                drawLimb(60, 52, 4, 12, 0, 8, false)
            } else {
                drawLimb(52, 20, 4, 12, 0, 8, true)
            }

            drawLimb(12, 20, 4, 12, 8, 20, false) // Right Leg Back
            if (is64x64) drawLimb(12, 36, 4, 12, 8, 20, false)

            if (is64x64) {
                drawLimb(28, 52, 4, 12, 4, 20, false) // Left Leg Back
                drawLimb(12, 52, 4, 12, 4, 20, false)
            } else {
                drawLimb(12, 20, 4, 12, 4, 20, true)
            }
        }

        g.dispose()
        return dest
    }
}
