package hivens.ui.customization

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import hivens.ui.theme.NxTheme

/**
 * Resolves the active surface tint at [baseAlpha], scaled by the user's glass intensity
 * preference. Applies under every style, not just Celestia -- default value 1.0 preserves
 * each style's natural opacity, lower values let the user opt into translucency.
 *
 * Light theme is the exception: an alpha-light surface over a wallpaper lands in mid-mud
 * and the wallpaper's high frequencies ride through as noise -- there is no good alpha
 * (Rule 4 / D29). So a light surface returns OPAQUE, reading as a body rather than a tint;
 * dark glass (src <= backdrop everywhere, a safe floor) keeps its translucency and the
 * glass-intensity knob. This is a stopgap -- the real fix is the body-carrying NxSurface.
 *
 * Use this anywhere that currently does `NxTheme.colors.surface.copy(alpha = X)`.
 */
@Composable
fun glassSurfaceAlpha(baseAlpha: Float): Color {
    val surface = NxTheme.colors.surface
    if (surface.luminance() > 0.5f) return surface
    val multiplier = LocalCustomization.current.glassIntensity
    return surface.copy(alpha = (baseAlpha * multiplier).coerceIn(0f, 1f))
}

/**
 * Same as [glassSurfaceAlpha] but takes a pre-resolved base color instead of pulling from
 * the palette. A light base returns opaque for the same Rule 4 reason; a dark base honours
 * the glass-intensity knob.
 */
@Composable
fun scaledAlpha(baseColor: Color, baseAlpha: Float): Color {
    if (baseColor.luminance() > 0.5f) return baseColor
    val multiplier = LocalCustomization.current.glassIntensity
    return baseColor.copy(alpha = (baseAlpha * multiplier).coerceIn(0f, 1f))
}
