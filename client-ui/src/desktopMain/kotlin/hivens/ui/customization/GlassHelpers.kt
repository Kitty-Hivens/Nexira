package hivens.ui.customization

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.ui.theme.CardSurface
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Resolves the active surface tint at [baseAlpha], scaled by the
 * user's glass intensity preference -- but only when the active
 * style is glass-capable. Brut's [CardSurface.Flat] short-circuits
 * to the original [baseAlpha] so opaque surfaces stay opaque
 * regardless of the customization knob (its identity).
 *
 * Use this anywhere that currently does
 * `CelestiaTheme.colors.surface.copy(alpha = X)` to make the
 * surface honor [hivens.ui.customization.CustomizationSettings.glassIntensity].
 */
@Composable
fun glassSurfaceAlpha(baseAlpha: Float): Color {
    val style      = LocalStyle.current
    val multiplier = if (style.cardSurface == CardSurface.Glass) {
        LocalCustomization.current.glassIntensity
    } else {
        1f
    }
    return CelestiaTheme.colors.surface.copy(
        alpha = (baseAlpha * multiplier).coerceIn(0f, 1f),
    )
}
