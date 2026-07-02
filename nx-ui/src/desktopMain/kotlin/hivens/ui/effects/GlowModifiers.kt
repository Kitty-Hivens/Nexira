package hivens.ui.effects

import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Pulsating outer glow -- animates spread and alpha behind the composable
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("ModifierComposedModifier")
fun Modifier.pulsatingGlow(
    color: Color,
    enabled: Boolean = true,
    // No default on purpose: the caller passes its own shape token
    // (LocalStyle cardCorner/buttonCorner), so the glow can't mismatch the host.
    cornerRadius: Dp,
): Modifier = composed {
    if (!enabled) return@composed this

    val inf = rememberInfiniteTransition(label = "pulsatingGlow")
    val intensity by inf.animateFloat(
        initialValue = 0.18f,
        targetValue  = 0.72f,
        animationSpec = infiniteRepeatable(
            tween(950, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "glowIntensity"
    )

    drawBehind {
        val cPx = cornerRadius.toPx()
        for (i in 1..3) {
            val spread = i * 8f * intensity
            drawRoundRect(
                color    = color.copy(alpha = intensity * 0.45f / i),
                topLeft  = Offset(-spread * 0.5f, -spread * 0.5f),
                size     = Size(size.width + spread, size.height + spread),
                cornerRadius = CornerRadius(cPx + spread * 0.2f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer overlay -- a light sweep drawn above the content
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("ModifierComposedModifier")
fun Modifier.shimmerOverlay(
    enabled: Boolean = true,
    highlightColor: Color = Color.White.copy(alpha = 0.09f)
): Modifier = composed {
    if (!enabled) return@composed this

    val inf = rememberInfiniteTransition(label = "shimmer")
    val offsetX by inf.animateFloat(
        initialValue = -1.1f,
        targetValue  =  2.1f,
        animationSpec = infiniteRepeatable(
            tween(3_200, delayMillis = 700, easing = FastOutSlowInEasing),
            RepeatMode.Restart
        ),
        label = "shimmerX"
    )

    drawWithContent {
        drawContent()
        val center = size.width * offsetX
        val half   = size.width * 0.30f
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, highlightColor, Color.Transparent),
                startX = center - half,
                endX   = center + half
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Neon animated border -- pulsates inside clip bounds, no overflow issues
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("ModifierComposedModifier")
fun Modifier.neonBorder(
    color: Color,
    // No default on purpose -- same contract as pulsatingGlow.
    cornerRadius: Dp,
    strokeWidth: Dp = 2.dp
): Modifier = composed {
    val inf = rememberInfiniteTransition(label = "neonBorder")
    val alpha by inf.animateFloat(
        initialValue = 0.50f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(
            tween(1_100, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "neonAlpha"
    )

    drawWithContent {
        drawContent()
        val sw = strokeWidth.toPx()
        val cr = cornerRadius.toPx()
        val inset = sw * 0.5f

        // Soft outer ring
        drawRoundRect(
            color  = color.copy(alpha = alpha * 0.30f),
            topLeft = Offset(inset, inset),
            size   = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(cr),
            style  = Stroke(sw * 4f)
        )
        // Sharp bright border
        drawRoundRect(
            color  = color.copy(alpha = alpha),
            topLeft = Offset(inset, inset),
            size   = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(cr),
            style  = Stroke(sw)
        )
    }
}

