package hivens.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import hivens.core.data.SeasonTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    var x: Float,
    var y: Float,
    var size: Float,
    var speedY: Float,
    var speedX: Float,
    var alpha: Float,
    var angle: Float = 0f,
    var angularVelocity: Float = 0f,
    var typeVariant: Int = 0,
    var phase: Float = Random.nextFloat() * 2 * PI.toFloat()
)

@Composable
fun SeasonalEffectsLayer(
    theme: SeasonTheme
) {
    val currentTheme = if (theme == SeasonTheme.AUTO) SeasonTheme.getCurrentSeasonalTheme() else theme

    if (currentTheme == SeasonTheme.NONE) return

    val density = when (currentTheme) {
        SeasonTheme.NEW_YEAR -> 150
        SeasonTheme.WINTER -> 100
        SeasonTheme.SPRING -> 60 
        SeasonTheme.AUTUMN -> 50
        SeasonTheme.SUMMER -> 40
        else -> 0
    }

    if (density == 0) return

    val infiniteTransition = rememberInfiniteTransition()
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val particles = remember(currentTheme) {
        List(density) { generateRandomParticle(currentTheme) }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val dt = 0.5f
        val width = size.width
        val height = size.height
        
        // Если размеры ещё не определены
        if (width <= 0 || height <= 0) return@Canvas

        particles.forEach { p ->
            updateParticle(p, currentTheme, width, height, dt)
            drawParticle(this, p, currentTheme)
        }
        
        if (time < 0) return@Canvas 
    }
}

private fun generateRandomParticle(theme: SeasonTheme): Particle {
    val random = Random
    return when (theme) {
        SeasonTheme.SUMMER -> { 
            // Светлячки летят вверх
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = random.nextFloat() * 3f + 2f,
                speedY = -(random.nextFloat() * 0.5f + 0.2f),
                speedX = (random.nextFloat() - 0.5f) * 0.5f,
                alpha = random.nextFloat() * 0.5f + 0.3f
            )
        }
        SeasonTheme.SPRING -> {
            // Сакура
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = random.nextFloat() * 8f + 6f,
                speedY = random.nextFloat() * 1.5f + 0.5f,
                speedX = (random.nextFloat() - 0.5f) * 0.5f,
                alpha = 0.8f,
                angle = random.nextFloat() * 360f,
                angularVelocity = (random.nextFloat() - 0.5f) * 2f
            )
        }
        SeasonTheme.AUTUMN -> {
            // Листья
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = random.nextFloat() * 10f + 8f,
                speedY = random.nextFloat() * 2f + 1f,
                speedX = (random.nextFloat() - 0.5f) * 1f,
                alpha = 0.9f,
                angle = random.nextFloat() * 360f,
                angularVelocity = (random.nextFloat() - 0.5f) * 5f,
                typeVariant = random.nextInt(3)
            )
        }
        else -> { 
            // Снег
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = random.nextFloat() * 3f + 1f,
                speedY = random.nextFloat() * 2f + 1.5f,
                speedX = (random.nextFloat() - 0.5f) * 0.5f,
                alpha = random.nextFloat() * 0.6f + 0.2f
            )
        }
    }
}

private fun updateParticle(p: Particle, theme: SeasonTheme, w: Float, h: Float, dt: Float) {
    // Инициализация координат при первом запуске (раздуваем из 0..1 в пиксели)
    if (p.x in 0f..1.0f && w > 1f) {
        p.x *= w
        p.y *= h
    }

    p.y += p.speedY * dt
    p.x += p.speedX * dt
    
    p.phase += 0.05f
    
    when (theme) {
        SeasonTheme.SPRING, SeasonTheme.AUTUMN -> {
            p.x += sin(p.phase) * 0.5f
            p.angle += p.angularVelocity * dt
        }
        SeasonTheme.SUMMER -> {
            p.x += sin(p.phase) * 0.3f
            p.alpha = (sin(p.phase * 0.5f) + 1f) / 2f * 0.6f + 0.2f
        }
        else -> {
            p.x += sin(p.phase) * 0.2f
        }
    }

    // Респаун
    if (theme == SeasonTheme.SUMMER) {
        if (p.y < -50f) {
            p.y = h + 10f
            p.x = Random.nextFloat() * w
        }
    } else {
        if (p.y > h + 10f) {
            p.y = -10f
            p.x = Random.nextFloat() * w
        }
    }
    
    if (p.x > w + 20f) p.x = -20f
    if (p.x < -20f) p.x = w + 20f
}

private fun drawParticle(scope: DrawScope, p: Particle, theme: SeasonTheme) {
    when (theme) {
        SeasonTheme.SPRING -> drawSakuraPetal(scope, p)
        SeasonTheme.AUTUMN -> drawAutumnLeaf(scope, p)
        SeasonTheme.SUMMER -> drawFirefly(scope, p)
        SeasonTheme.NEW_YEAR -> {
            drawSnowflake(scope, p)
            if (Random.nextFloat() > 0.98f) {
                scope.drawCircle(
                    color = Color.Yellow.copy(alpha = p.alpha),
                    radius = p.size * 0.8f,
                    center = Offset(p.x, p.y)
                )
            }
        }
        else -> drawSnowflake(scope, p)
    }
}

private fun drawSnowflake(scope: DrawScope, p: Particle) {
    scope.drawCircle(
        color = Color.White.copy(alpha = p.alpha),
        radius = p.size,
        center = Offset(p.x, p.y)
    )
}

private fun drawFirefly(scope: DrawScope, p: Particle) {
    scope.drawCircle(
        color = Color(0xFFCDDC39).copy(alpha = p.alpha),
        radius = p.size,
        center = Offset(p.x, p.y)
    )
    scope.drawCircle(
        color = Color(0xFFCDDC39).copy(alpha = p.alpha * 0.3f),
        radius = p.size * 2.5f,
        center = Offset(p.x, p.y)
    )
}

private fun drawSakuraPetal(scope: DrawScope, p: Particle) {
    val sakuraColor = Color(0xFFFFB7C5).copy(alpha = p.alpha)
    scope.withTransform({
        rotate(degrees = p.angle, pivot = Offset(p.x, p.y))
        translate(left = p.x, top = p.y)
    }) {
        drawOval(
            color = sakuraColor,
            topLeft = Offset(-p.size / 2, -p.size / 4),
            size = Size(p.size, p.size / 1.5f)
        )
    }
}

private fun drawAutumnLeaf(scope: DrawScope, p: Particle) {
    val leafColor = when (p.typeVariant) {
        0 -> Color(0xFFD84315)
        1 -> Color(0xFFFF8F00)
        else -> Color(0xFF5D4037)
    }.copy(alpha = p.alpha)

    scope.withTransform({
        rotate(degrees = p.angle, pivot = Offset(p.x, p.y))
        translate(left = p.x, top = p.y)
    }) {
        val path = Path().apply {
            moveTo(0f, -p.size)
            lineTo(p.size / 2, 0f)
            lineTo(0f, p.size)
            lineTo(-p.size / 2, 0f)
            close()
        }
        drawPath(path = path, color = leafColor)
    }
}
