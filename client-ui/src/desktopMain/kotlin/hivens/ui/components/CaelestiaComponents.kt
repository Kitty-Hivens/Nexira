package hivens.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.effects.pulsatingGlow
import hivens.ui.theme.CardSurface
import hivens.ui.theme.CelestiaTheme
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
    val palette        = CelestiaTheme.colors
    val customization  = LocalCustomization.current
    val resolvedShape  = shape ?: RoundedCornerShape(style.cardCorner)
    val resolvedBackground = backgroundColor ?: when (style.cardSurface) {
        CardSurface.Glass -> palette.glassBackground.copy(
            alpha = (palette.glassAlpha * customization.glassIntensity).coerceIn(0f, 1f),
        )
        CardSurface.Flat  -> MaterialTheme.colorScheme.surface
    }
    val resolvedBorderWidth = style.cardBorder.coerceAtLeast(0.dp)
    val resolvedBorder = when {
        borderColor != Color.Unspecified -> borderColor
        MaterialTheme.colorScheme.surface.luminance() < 0.5f -> Color(0xFF2C2C2C)
        else -> Color(0xFFCCCCCC)
    }

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

/**
 * Celestia button.
 *
 * [glowing] = true enables a pulsing neon halo -- meant for the PLAY
 * button while it sits idle.
 */
@Composable
fun CelestiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    glowing: Boolean = false
) {
    val style = LocalStyle.current
    val glowColor = MaterialTheme.colorScheme.primary
    val buttonShape = RoundedCornerShape(style.buttonCorner)
    // Glow attaches only when the active style enables decorative glow.
    // BrutStyle suppresses it without the call site having to know.
    val showGlow = glowing && style.softGlowEnabled

    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier
                .height(48.dp)
                .let { if (showGlow) it.pulsatingGlow(glowColor, cornerRadius = style.buttonCorner) else it },
            enabled = enabled,
            shape = buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor = Color.White,
                disabledContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.6f)
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            enabled = enabled,
            shape = buttonShape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = BorderStroke(1.dp, Color(0xFF444444))
        ) {
            Text(text)
        }
    }
}
