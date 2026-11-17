package hivens.ui.effects

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.theme.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme

// ─────────────────────────────────────────────────────────────────────────────
// Pulsating outer glow -- animates spread and alpha behind the composable
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("ModifierComposedModifier")
fun Modifier.pulsatingGlow(
    color: Color,
    enabled: Boolean = true,
    // No default on purpose: the caller passes its own shape token
    // the host is clipped to, so the glow can't mismatch it.
    cornerRadius: Dp,
): Modifier = composed {
    if (!enabled) return@composed this

    val pulse = Motion.ownRhythm(PULSE_MS)
    val intensity by rememberInfiniteTransition(label = "pulsatingGlow").animateFloat(
        initialValue = GLOW_REST,
        targetValue  = 0.72f,
        animationSpec = infiniteRepeatable(pulse.of(), RepeatMode.Reverse),
        label = "glowIntensity",
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

    val sweepRhythm = Motion.ownRhythm(SHIMMER_MS)
    val offsetX by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1.1f,
        targetValue  =  2.1f,
        animationSpec = infiniteRepeatable(
            tween(sweepRhythm.durationMs, delayMillis = SHIMMER_GAP_MS, easing = sweepRhythm.easing),
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
    val neonRhythm = Motion.ownRhythm(NEON_MS)
    val alpha by rememberInfiniteTransition(label = "neonBorder").animateFloat(
        initialValue = 0.50f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(neonRhythm.of(), RepeatMode.Reverse),
        label = "neonAlpha",
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

// Periods of the decorative effects above. Artistic choices rather than roles on
// the motion scale, but declared through Motion.ownRhythm so the scale stays the
// one place a duration is spelled.
private const val PULSE_MS = 950
private const val SHIMMER_MS = 3_200
private const val SHIMMER_GAP_MS = 700
private const val NEON_MS = 1_100
private const val GLOW_REST = 0.18f

@Preview
@Composable
private fun GlowModifiersDarkPreview() = GlowEffects(dark = true)

@Preview
@Composable
private fun GlowModifiersLightPreview() = GlowEffects(dark = false)

@Composable
private fun GlowEffects(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(520.dp).background(NxColor.page).padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            val corner = 12.dp
            GlowSample("Pulsating glow", Modifier.pulsatingGlow(NxColor.lead(), cornerRadius = corner))
            GlowSample("Shimmer", Modifier.shimmerOverlay(highlightColor = NxInk.main.copy(alpha = 0.3f)))
            GlowSample("Neon border", Modifier.neonBorder(NxColor.lead(), cornerRadius = corner))
        }
    }
}

@Composable
private fun GlowSample(label: String, effect: Modifier) {
    NxSurface(SurfaceKind.Card, effect.size(120.dp, 72.dp).clip(MaterialTheme.shapes.medium)) {
        Box(Modifier.align(Alignment.Center)) { Text(label, style = MaterialTheme.typography.labelMedium, color = NxInk.main) }
    }
}
