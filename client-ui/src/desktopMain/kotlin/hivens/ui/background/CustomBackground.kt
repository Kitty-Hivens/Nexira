package hivens.ui.background

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.makeFromFileName
import java.io.File

/**
 * Renders a custom background wallpaper behind the main app content.
 *
 * Supports: blur, darkening, opacity, parallax, vignette, color tint,
 * multiple scale modes, alignment control, and hardware-accelerated animated GIFs (via Skiko).
 */
@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier,
    mousePosProvider: () -> Offset = { Offset(0.5f, 0.5f) }
) {
    if (!settings.enabled || settings.imagePath.isNullOrBlank()) return
    val file = File(settings.imagePath)
    if (!file.exists()) return

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedParallaxImage(
            file = file,
            settings = settings,
            mousePosProvider = mousePosProvider
        )

        // Darkening overlay
        if (settings.darkenAmount > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.darkenAmount)))
        }

        // Color tint overlay
        if (settings.tintColor != null && settings.tintOpacity > 0f) {
            val tint = try {
                Color(("FF" + settings.tintColor.removePrefix("#")).toLong(16))
            } catch (_: Exception) { null }
            if (tint != null) {
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = settings.tintOpacity)))
            }
        }

        // Vignette overlay
        if (settings.vignetteIntensity > 0f) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawIntoCanvas {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val radius = maxOf(size.width, size.height) * 0.7f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = settings.vignetteIntensity * 0.6f)
                                ),
                                center = Offset(centerX, centerY),
                                radius = radius
                            ),
                            center = Offset(centerX, centerY),
                            radius = radius * 1.5f
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AnimatedParallaxImage(
    file: File,
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset
) {
    val contentScale = when (settings.scaleMode) {
        ScaleMode.COVER    -> ContentScale.Crop
        ScaleMode.CONTAIN  -> ContentScale.Fit
        ScaleMode.STRETCH  -> ContentScale.FillBounds
        ScaleMode.ORIGINAL -> ContentScale.None
        ScaleMode.TILE     -> ContentScale.None
    }

    val alignment = Alignment { size, space, _ ->
        val x = ((space.width - size.width) * settings.alignX).toInt()
        val y = ((space.height - size.height) * settings.alignY).toInt()
        IntOffset(x, y)
    }

    val imageBitmap = rememberSkiaImage(file)

    if (imageBitmap != null) {
        val useParallax = settings.parallaxIntensity > 0f
        val baseModifier = Modifier
            .fillMaxSize()
            .let {
                if (settings.blurRadius > 0f)
                    it.blur(settings.blurRadius.dp, BlurredEdgeTreatment.Unbounded)
                else it
            }
            .alpha(settings.opacity)

        if (useParallax) {
            val mousePos = mousePosProvider()
            val targetX = (0.5f - mousePos.x) * settings.parallaxIntensity * 80f
            val targetY = (0.5f - mousePos.y) * settings.parallaxIntensity * 80f
            val parallaxX by animateFloatAsState(
                targetValue = targetX,
                animationSpec = spring(stiffness = 50f, dampingRatio = 0.8f)
            )
            val parallaxY by animateFloatAsState(
                targetValue = targetY,
                animationSpec = spring(stiffness = 50f, dampingRatio = 0.8f)
            )
            Image(
                painter = BitmapPainter(imageBitmap),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = baseModifier.graphicsLayer {
                    val extraScale = 1f + settings.parallaxIntensity * 0.15f
                    scaleX = extraScale
                    scaleY = extraScale
                    translationX = parallaxX
                    translationY = parallaxY
                }
            )
        } else {
            Image(
                painter = BitmapPainter(imageBitmap),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = baseModifier
            )
        }
    }
}

/**
 * Converts a Skia Bitmap to ImageBitmap without leaking a Canvas.
 *
 * The standard toComposeImageBitmap() creates a Canvas internally and never
 * closes it, causing native memory to accumulate over time. This function
 * explicitly closes the Canvas after use.
 */
private fun Bitmap.toImageBitmapSafe(): ImageBitmap {
    val dst = Bitmap()
    dst.allocPixels(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL))
    val canvas = Canvas(dst)
    try {
        val image = org.jetbrains.skia.Image.makeFromBitmap(this)
        try {
            canvas.drawImage(image, 0f, 0f)
        } finally {
            image.close()
        }
    } finally {
        canvas.close()
    }
    val result = dst.asComposeImageBitmap()
    dst.close()
    return result
}

@Composable
private fun rememberSkiaImage(file: File): ImageBitmap? {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        if (!file.exists()) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            var data:  Data?   = null
            var codec: Codec?  = null
            var bmp:   Bitmap? = null

            try {
                data  = Data.makeFromFileName(file.absolutePath)
                codec = Codec.makeFromData(data)
                bmp   = Bitmap().apply { allocPixels(codec.imageInfo) }

                if (codec.frameCount <= 1) {
                    // Static image — decode once, convert safely, done
                    codec.readPixels(bmp, 0)
                    bitmap = bmp.toImageBitmapSafe()
                } else {
                    // Animated GIF/WebP — decode frame by frame
                    var frame      = 0
                    var priorFrame = -1

                    while (isActive) {
                        if (frame == 0) { bmp.erase(0); priorFrame = -1 }

                        codec.readPixels(bmp, frame, priorFrame)
                        // Use safe conversion to avoid Canvas leak on every frame
                        bitmap = bmp.toImageBitmapSafe()

                        var duration = codec.framesInfo[frame].duration
                        if (duration < 30) duration = 100

                        delay(duration.toLong())

                        priorFrame = frame
                        frame = (frame + 1) % codec.frameCount
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bmp?.close()
                codec?.close()
                data?.close()
            }
        }
    }
    return bitmap
}
