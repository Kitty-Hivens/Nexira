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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

private val logger = LoggerFactory.getLogger("CustomBackground")

/**
 * Renders a custom background wallpaper behind the main app content.
 *
 * Supports: blur, darkening, opacity, parallax, vignette, color tint,
 * multiple scale modes, and alignment control.
 */
@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier
) {
    if (!settings.enabled || settings.imagePath.isNullOrBlank()) return

    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var mousePos by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    // Load image — uses same pattern as SquareServerCard
    LaunchedEffect(settings.imagePath) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val file = File(settings.imagePath)
                if (!file.exists()) {
                    logger.warn("Background image not found: {}", settings.imagePath)
                    return@withContext null
                }
                ImageIO.read(file)?.toComposeImageBitmap()
            } catch (e: Exception) {
                logger.error("Failed to load background image", e)
                null
            }
        }
    }

    val currentBitmap = bitmap ?: return

    val parallaxX by animateFloatAsState(
        (mousePos.x - 0.5f) * settings.parallaxIntensity * 30f,
        tween(300, easing = FastOutSlowInEasing)
    )
    val parallaxY by animateFloatAsState(
        (mousePos.y - 0.5f) * settings.parallaxIntensity * 30f,
        tween(300, easing = FastOutSlowInEasing)
    )

    val contentScale = when (settings.scaleMode) {
        ScaleMode.COVER -> ContentScale.Crop
        ScaleMode.CONTAIN -> ContentScale.Fit
        ScaleMode.STRETCH -> ContentScale.FillBounds
        ScaleMode.ORIGINAL -> ContentScale.None
        ScaleMode.TILE -> ContentScale.None
    }

    val alignment = Alignment { size, space, _ ->
        val x = ((space.width - size.width) * settings.alignX).toInt()
        val y = ((space.height - size.height) * settings.alignY).toInt()
        IntOffset(x, y)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(settings.parallaxIntensity) {
                if (settings.parallaxIntensity <= 0f) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) {
                            val pos = event.changes.firstOrNull()?.position ?: continue
                            if (containerSize.width > 0 && containerSize.height > 0) {
                                mousePos = Offset(pos.x / containerSize.width, pos.y / containerSize.height)
                            }
                        }
                    }
                }
            }
    ) {
        // Image layer
        Image(
            painter = BitmapPainter(currentBitmap),
            contentDescription = null,
            contentScale = contentScale,
            alignment = alignment,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (settings.parallaxIntensity > 0f) {
                        val extraScale = 1f + settings.parallaxIntensity * 0.08f
                        scaleX = extraScale
                        scaleY = extraScale
                        translationX = parallaxX
                        translationY = parallaxY
                    }
                }
                .let { if (settings.blurRadius > 0f) it.blur(settings.blurRadius.dp, BlurredEdgeTreatment.Unbounded) else it }
                .alpha(settings.opacity)
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
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = settings.vignetteIntensity * 0.6f)),
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
