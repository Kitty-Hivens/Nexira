package hivens.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.data.PackOrigin

// Brand colors for pack/server sources, plus the decorative hash-ramp for
// avatars. All read off the themed [NxColors] tokens, so the source
// badges, gradients and avatars follow the active palette (and any custom
// override) instead of the fixed hex they used to hardcode. Gradient and avatar
// shades are DERIVED here from a single base token, rather than maintained as
// separate, drift-prone literals (the issue's "mirror blue in three places").

/** Themed base color for a pack origin. */
fun NxColors.origin(origin: PackOrigin): Color = when (origin) {
    PackOrigin.Smartycraft -> originSmartycraft
    PackOrigin.Mirror      -> originMirror
    PackOrigin.Modrinth    -> originModrinth
    PackOrigin.Local,
    PackOrigin.Unknown     -> originLocal
}

/** Themed base color for a mirror source type; mirrors [origin]'s mapping. */
fun NxColors.source(source: SmrtSource): Color = when (source) {
    is SmrtSource.Modrinth   -> originModrinth
    is SmrtSource.SmrtCache  -> originMirror
    is SmrtSource.SmrtStatic -> originLocal
    is SmrtSource.Unknown    -> originLocal
}

/** Card hero gradient for an origin: the brand base shaded toward black. */
fun NxColors.originGradient(origin: PackOrigin): Brush {
    val base = origin(origin)
    return Brush.linearGradient(listOf(base, lerp(base, Color.Black, 0.35f)))
}

/** Stable decorative color for a name (server / mod), from the themed ramp. */
fun NxColors.decorativeColor(name: String): Color =
    decorativeRamp[Math.floorMod(name.hashCode(), decorativeRamp.size)]

/**
 * Stable decorative two-color pair for a name (two adjacent ramp colors), so a
 * server avatar keeps its two-hue gradient while staying in-theme. Callers build
 * the brush with their own alphas.
 */
fun NxColors.decorativePair(name: String): Pair<Color, Color> {
    val i = Math.floorMod(name.hashCode(), decorativeRamp.size)
    return decorativeRamp[i] to decorativeRamp[(i + 1) % decorativeRamp.size]
}
