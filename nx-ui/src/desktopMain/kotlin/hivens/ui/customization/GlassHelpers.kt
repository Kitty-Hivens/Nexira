package hivens.ui.customization

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme

/**
 * Resolves the active surface tint at [baseAlpha], scaled by the user's glass intensity
 * preference. Applies under every style, not just Celestia -- default value 1.0 preserves
 * each style's natural opacity, lower values let the user opt into translucency.
 *
 * Light theme spends that request differently. An alpha-light surface over a wallpaper
 * lands in mid-mud and the wallpaper's high frequencies ride through as noise, so there
 * is no good alpha there (Rule 4 / D29) and a light plane is always opaque. The depth
 * being asked for is real all the same, so it picks a rung of the tonal ladder instead
 * of an alpha -- see [lightPlaneFor]. Dark glass (src <= backdrop everywhere, a safe
 * floor) keeps its translucency and the glass-intensity knob.
 *
 * Still a stopgap: a call site that says `0.45` is guessing at a depth it could simply
 * name, and the ladder is what `NxSurface(level = ...)` already takes by name. What this
 * fixes is that the guess used to be discarded entirely on light.
 *
 * Use this anywhere that currently does `NxTheme.colors.surface.copy(alpha = X)`.
 */
@Composable
fun glassSurfaceAlpha(baseAlpha: Float): Color {
    val colors = NxTheme.colors
    if (colors.surface.luminance() > 0.5f) return lightPlaneFor(baseAlpha, colors)
    val multiplier = LocalCustomization.current.glassIntensity
    return colors.surface.copy(alpha = (baseAlpha * multiplier).coerceIn(0f, 1f))
}

/**
 * The light answer to a requested depth.
 *
 * On dark, [baseAlpha] decides how much of the surface tint sits over the page, so
 * a larger number is a plane that stands further off it. There is no alpha to spend
 * on light -- a body is opaque there -- so the same number picks a rung of the tonal
 * ladder instead, in the same direction: more depth, further from the page.
 *
 * It snaps to a rung rather than interpolating. Only five colours may back a plane,
 * and a continuous blend would put shades between them that nothing else in the
 * system knows about and no test can hold to a separation rule.
 *
 * The shallow end has to land on `surface`, not a rung above it. `FrostTier.Clear`
 * tints from its own Surface role at 0.35 and the shell's corner wedge is drawn
 * with `glassSurfaceAlpha(0.35)` precisely so the two agree -- the wedge exists to
 * carry the content's corner into the chrome, and it can only do that while it is
 * the same colour. Splitting them put a visible patch where the join had been.
 *
 * This used to return `surface` for every value. Eight distinct depths across
 * forty-odd call sites all came out as one pixel, which is why a card, the page it
 * sat on and a panel nested inside it were literally the same colour.
 */
private fun lightPlaneFor(baseAlpha: Float, colors: NxColors): Color = when {
    baseAlpha <= 0.50f -> colors.surface
    baseAlpha < 0.70f  -> colors.surfaceContainer
    else               -> colors.surfaceContainerHigh
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
