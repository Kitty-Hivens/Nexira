package hivens.ui.customization

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.ui.theme.CelestiaTheme

/**
 * Resolves the active surface tint at [baseAlpha], scaled by the
 * user's glass intensity preference. Applies under every style,
 * not just Celestia -- default value 1.0 preserves each style's
 * natural opacity, lower values let the user opt into translucency
 * even under Brut.
 *
 * Use this anywhere that currently does
 * `CelestiaTheme.colors.surface.copy(alpha = X)` to make the
 * surface honor [hivens.ui.customization.CustomizationSettings.glassIntensity].
 */
@Composable
fun glassSurfaceAlpha(baseAlpha: Float): Color {
    val multiplier = LocalCustomization.current.glassIntensity
    return CelestiaTheme.colors.surface.copy(
        alpha = (baseAlpha * multiplier).coerceIn(0f, 1f),
    )
}
