package hivens.ui.nx

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.effects.pulsatingGlow
import hivens.ui.theme.CardSurface
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * Theme-aware container card. Reads form / surface tokens from
 * [LocalStyle], so the same call site looks different under CelestiaStyle
 * (rounded corners, glass-alpha fill, no visible border) vs BrutStyle
 * (near-square corners, flat opaque fill, hard 1dp border).
 *
 * [borderColor] override stays available for the rare call site that
 * wants a deliberate accent; left unspecified, light theme picks light
 * gray and dark theme picks dark gray.
 *
 * [shape] override exists for cases like ServerDetail's banner area
 * that want a specific shape regardless of style variant. Default
 * pulls from the active style, so most callers don't need to pass it.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    backgroundColor: Color? = null,
    borderColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val style          = LocalStyle.current
    val palette        = NxTheme.colors
    val customization  = LocalCustomization.current
    val resolvedShape  = shape ?: RoundedCornerShape(style.cardCorner)
    val resolvedBackground = backgroundColor ?: when (style.cardSurface) {
        CardSurface.Glass -> palette.glassBackground.copy(
            alpha = (palette.glassAlpha * customization.glassIntensity).coerceIn(0f, 1f),
        )
        // Brut: at intensity = 1.0 keep the solid grey surface
        // (Brut identity). As intensity drops, lerp the colour
        // toward palette.glassBackground (the same near-black tint
        // Celestia uses) AND drop alpha. The colour shift makes the
        // translucency visually obvious -- grey alone at low alpha
        // reads as "darker grey" rather than "see-through".
        CardSurface.Flat  -> {
            val intensity = customization.glassIntensity.coerceIn(0f, 1f)
            androidx.compose.ui.graphics.lerp(
                palette.glassBackground,
                MaterialTheme.colorScheme.surface,
                intensity,
            ).copy(alpha = intensity)
        }
    }
    val resolvedBorderWidth = style.cardBorder.coerceAtLeast(0.dp)
    val resolvedBorder = if (borderColor != Color.Unspecified) borderColor else palette.outline

    OutlinedCard(
        modifier  = modifier,
        shape     = resolvedShape,
        colors    = CardDefaults.outlinedCardColors(containerColor = resolvedBackground),
        border    = BorderStroke(if (resolvedBorderWidth > 0.dp) resolvedBorderWidth else 1.dp, resolvedBorder),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp)
    ) {
        Box(content = content)
    }
}
