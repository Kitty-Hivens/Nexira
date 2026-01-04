package hivens.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Менеджер скинов для SmartyCraft.
 * Логика рендеринга адаптирована под h.java из исходников лаунчера.
 */
object SkinManager {
    private const val BASE_SKIN_URL = "https://www.smartycraft.ru/skins/"
    private const val BASE_CLOAK_URL = "https://www.smartycraft.ru/cloaks/"

    private val frontCache = mutableMapOf<String, ImageBitmap>()
    private val backCache = mutableMapOf<String, ImageBitmap>()

    // Режим для обычных скинов (64x32/64x64) - Пиксельная четкость (как в Minecraft)
    private val samplingNearest = org.jetbrains.skia.FilterMipmap(
        org.jetbrains.skia.FilterMode.NEAREST,
        org.jetbrains.skia.MipmapMode.NONE
    )

    // Режим для HD скинов (>64px) - Линейное сглаживание (убирает "шум" и рябь)
    private val samplingLinear = org.jetbrains.skia.FilterMipmap(
        org.jetbrains.skia.FilterMode.LINEAR,
        org.jetbrains.skia.MipmapMode.NONE
    )

    // Paint без антиалиасинга для четких границ деталей
    private val paint = org.jetbrains.skia.Paint().apply {
        isAntiAlias = false
    }

    fun invalidate() {
        frontCache.clear()
        backCache.clear()
    }

    fun invalidate(nickname: String) {
        frontCache.remove(nickname)
        backCache.remove(nickname)
    }

    suspend fun getSkinFront(nickname: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (frontCache.containsKey(nickname)) return@withContext frontCache[nickname]

        val rawSkin = downloadTexture("$BASE_SKIN_URL$nickname.png") ?: return@withContext null
        val processed = assembleSkin(rawSkin, isFront = true, cloak = null)

        val result = processed.asComposeImageBitmap()
        frontCache[nickname] = result
        return@withContext result
    }

    suspend fun getSkinBack(nickname: String, cloakHash: String? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        // Ключ кеша должен учитывать плащ, но для UI профиля пока хватит ника
        if (backCache.containsKey(nickname)) return@withContext backCache[nickname]

        val rawSkin = downloadTexture("$BASE_SKIN_URL$nickname.png") ?: return@withContext null

        // Логика плащей SmartyCraft: приоритет хешу, иначе пробуем по нику
        val cloakUrl = if (!cloakHash.isNullOrEmpty()) {
            "$BASE_CLOAK_URL$cloakHash.png"
        } else {
            "$BASE_CLOAK_URL$nickname.png"
        }
        val rawCloak = downloadTexture(cloakUrl)

        val processed = assembleSkin(rawSkin, isFront = false, cloak = rawCloak)

        val result = processed.asComposeImageBitmap()
        backCache[nickname] = result
        return@withContext result
    }

    private fun downloadTexture(url: String): org.jetbrains.skia.Image? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0") // Притворяемся браузером
            if (conn.responseCode != 200) return null

            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Сборка скина из кусочков.
     * Координаты и зеркалирование соответствуют h.java.
     */
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

        // Определение формата (HD / Legacy)
        val isHD = w > 64
        val k = w / 64f
        val isLegacy = h == 32f * k // Старый формат 64x32

        // Ширина руки (4px Steve, 3px Alex). h.java считает все Modern скины тонкими,
        // но для универсальности оставим 4px как дефолт.
        val armW = 4f
        val scale = 10f

        // Выбор режима сглаживания
        val samplingMode = if (isHD) samplingLinear else samplingNearest

        // Функция отрисовки части текстуры
        fun drawPart(
            srcX: Float, srcY: Float, srcW: Float, srcH: Float,
            dstX: Float, dstY: Float, dstW: Float, dstH: Float,
            mirror: Boolean = false
        ) {
            val srcRect = org.jetbrains.skia.Rect.makeXYWH(srcX * k, srcY * k, srcW * k, srcH * k)
            val dstRect = org.jetbrains.skia.Rect.makeXYWH(dstX * scale, dstY * scale, dstW * scale, dstH * scale)

            if (mirror) {
                canvas.save()
                // Зеркалирование относительно центра целевого прямоугольника
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

        // --- 1. ГОЛОВА (Head) ---
        val headSrcX = if (isFront) 8f else 24f
        drawPart(headSrcX, 8f, 8f, 8f, 4f, 0f, 8f, 8f)

        // Шлем / Аксессуар (Helm)
        val helmSrcX = if (isFront) 40f else 56f
        drawPart(helmSrcX, 8f, 8f, 8f, 4f, 0f, 8f, 8f)

        // --- 2. ТЕЛО (Body) ---
        val bodySrcX = if (isFront) 20f else 32f
        drawPart(bodySrcX, 20f, 8f, 12f, 4f, 8f, 8f, 12f)

        if (!isLegacy) {
            val body2SrcX = if (isFront) 20f else 32f
            drawPart(body2SrcX, 36f, 8f, 12f, 4f, 8f, 8f, 12f)
        }

        // --- 3. РУКИ (Arms) ---
        if (isFront) {
            // Вид спереди: Правая рука (слева на экране), Левая (справа)
            drawPart(44f, 20f, armW, 12f, 4f - armW, 8f, armW, 12f) // Right Arm Front

            val dstXLeft = 12f
            if (isLegacy) {
                // Левая рука = Зеркальная правая
                drawPart(44f, 20f, armW, 12f, dstXLeft, 8f, armW, 12f, mirror = true)
            } else {
                drawPart(36f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f) // Left Arm Front
                drawPart(52f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f) // Overlay
                drawPart(44f, 36f, armW, 12f, 4f - armW, 8f, armW, 12f) // Right Overlay
            }
        } else {
            // Вид сзади: Левая рука (слева на экране), Правая (справа)
            val dstXRight = 12f // Screen Right -> Character Right Arm Back
            val dstXLeft = 4f - armW // Screen Left -> Character Left Arm Back

            // Right Arm Back (Texture 52,20)
            drawPart(52f, 20f, armW, 12f, dstXRight, 8f, armW, 12f)
            if (!isLegacy) drawPart(52f, 36f, armW, 12f, dstXRight, 8f, armW, 12f)

            // Left Arm Back
            if (isLegacy) {
                // Зеркалим Right Arm Back для левой руки
                drawPart(52f, 20f, armW, 12f, dstXLeft, 8f, armW, 12f, mirror = true)
            } else {
                drawPart(44f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f) // Left Arm Back
                drawPart(60f, 52f, armW, 12f, dstXLeft, 8f, armW, 12f) // Overlay
            }
        }

        // --- 4. НОГИ (Legs) ---
        // Right Leg (Texture 0,20 / 12,20)
        if (isFront) {
            drawPart(4f, 20f, 4f, 12f, 4f, 20f, 4f, 12f) // Front
            if (!isLegacy) drawPart(4f, 36f, 4f, 12f, 4f, 20f, 4f, 12f)
        } else {
            // Back View: Рисуем Right Leg (12,20) справа на экране
            drawPart(12f, 20f, 4f, 12f, 8f, 20f, 4f, 12f)
            if (!isLegacy) drawPart(12f, 36f, 4f, 12f, 8f, 20f, 4f, 12f)
        }

        // Left Leg
        if (isLegacy) {
            // Зеркалим правую ногу
            if (isFront) drawPart(4f, 20f, 4f, 12f, 8f, 20f, 4f, 12f, mirror = true)
            else drawPart(12f, 20f, 4f, 12f, 4f, 20f, 4f, 12f, mirror = true) // Back View Mirror
        } else {
            if (isFront) {
                drawPart(20f, 52f, 4f, 12f, 8f, 20f, 4f, 12f)
                drawPart(4f, 52f, 4f, 12f, 8f, 20f, 4f, 12f)
            } else {
                drawPart(28f, 52f, 4f, 12f, 4f, 20f, 4f, 12f)
                drawPart(12f, 52f, 4f, 12f, 4f, 20f, 4f, 12f)
            }
        }

        // --- 5. ПЛАЩ (Cloak) ---
        // Рисуем в самом конце, чтобы он перекрывал тело и руки (как рюкзак)
        if (!isFront && cloak != null) {
            val kCloak = cloak.width.toFloat() / 64f
            val isCloakHD = cloak.width > 64
            // Плащ может быть HD, даже если скин обычный
            val cloakSampling = if (isCloakHD) samplingLinear else samplingNearest

            // ВАЖНО: h.java использует координаты (1,1) (Front) для спины плаща.
            // Стандартный (12,1) там не используется.
            val cloakSrc = org.jetbrains.skia.Rect.makeXYWH(1f * kCloak, 1f * kCloak, 10f * kCloak, 16f * kCloak)
            val cloakDst = org.jetbrains.skia.Rect.makeXYWH(3f * scale, 8f * scale, 10f * scale, 16f * scale)

            canvas.drawImageRect(cloak, cloakSrc, cloakDst, cloakSampling, paint, true)
        }

        return output
    }
}
