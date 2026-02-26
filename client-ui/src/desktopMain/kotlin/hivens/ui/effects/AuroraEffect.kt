package hivens.ui.effects

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import kotlin.math.*

private data class Star(val x: Float, val y: Float, val size: Float, val phase: Float)

private data class AuroraBand(
    val yFrac: Float,
    val color: Color,
    val maxAlpha: Float,
    val freqScale: Float = 1f
)

/**
 * Animated aurora borealis — multi-layered sine-wave bands that shift and pulse.
 * Adapts to dark/light theme via [alphaScale].
 */
@Composable
fun AuroraEffect(
    isDarkTheme: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "aurora")

    val t1 by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(20_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "auroraT1"
    )
    val t2 by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(31_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "auroraT2"
    )

    val alphaScale = if (isDarkTheme) 1f else 0.25f
    val accentTeal = Color(0xFF64FFDA)

    val bands = remember(primaryColor, secondaryColor) {
        listOf(
            AuroraBand(0.07f, primaryColor,   0.09f, 1.00f),
            AuroraBand(0.13f, secondaryColor, 0.07f, 0.75f),
            AuroraBand(0.05f, accentTeal,     0.05f, 1.40f),
            AuroraBand(0.17f, primaryColor,   0.04f, 0.55f),
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        bands.forEachIndexed { i, band ->
            val yCenter  = h * band.yFrac
            val amplitude = h * (0.04f + 0.015f * sin(t2 + i.toFloat()))
            val freq     = 0.0022f * band.freqScale
            val phase    = t1 + i * 1.4f

            val path = Path()
            path.moveTo(0f, h)

            var x = 0f
            var firstPoint = true
            while (x <= w + 6f) {
                val cx = x.coerceAtMost(w)
                val y = yCenter +
                    sin(cx * freq + phase) * amplitude +
                    cos(cx * freq * 0.55f + t2 + i.toFloat()) * amplitude * 0.45f
                if (firstPoint) { path.moveTo(0f, y); firstPoint = false }
                path.lineTo(cx, y)
                x += 6f
            }
            path.lineTo(w, h)
            path.close()

            val bandH     = h * 0.20f
            val pulseAlpha = band.maxAlpha * alphaScale *
                (0.65f + 0.35f * sin(t1 * 0.45f + i.toFloat() * 0.9f))

            drawPath(
                path  = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        band.color.copy(alpha = pulseAlpha),
                        band.color.copy(alpha = pulseAlpha * 0.35f),
                        Color.Transparent,
                    ),
                    startY = (yCenter - bandH * 0.4f).coerceAtLeast(0f),
                    endY   = (yCenter + bandH * 0.9f).coerceAtMost(h)
                )
            )
        }
    }
}

/**
 * Twinkling star field — only intended for dark theme.
 * 200 stars at random positions with randomised phase offsets.
 */
@Composable
fun StarFieldEffect(modifier: Modifier = Modifier) {
    val stars = remember {
        List(200) {
            Star(
                x     = kotlin.random.Random.nextFloat(),
                y     = kotlin.random.Random.nextFloat(),
                size  = kotlin.random.Random.nextFloat() * 1.2f + 0.2f,
                phase = kotlin.random.Random.nextFloat() * (2.0 * PI).toFloat()
            )
        }
    }

    val t by rememberInfiniteTransition(label = "stars").animateFloat(
        initialValue = 0f,
        targetValue  = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(8_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "starTime"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        stars.forEach { star ->
            val alpha = ((sin(t * 0.75f + star.phase) + 1f) / 2f * 0.55f + 0.1f)
                .coerceIn(0f, 1f)
            drawCircle(
                color  = Color.White.copy(alpha = alpha),
                radius = star.size,
                center = Offset(star.x * w, star.y * h)
            )
        }
    }
}
