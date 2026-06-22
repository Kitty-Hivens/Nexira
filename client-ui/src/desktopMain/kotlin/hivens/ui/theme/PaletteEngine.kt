package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * Material You colour science -> [CelestiaColors] (Monet). Given a seed colour
 * (extracted from the wallpaper), build an M3 tonal scheme and map its roles onto
 * [base], replacing the palette-derived fields -- accents, surfaces, outline,
 * on-colours -- while keeping brand / semantic tokens (origin*, severity accents,
 * decorativeRamp, glass) from [base]. The surface tiers come out tinted toward the
 * seed at their elevation, so two planes differ by COLOUR, not just lightness --
 * which is the whole point (same-on-same never reads as separate).
 *
 * Pure: no Compose state, no IO -- unit-testable. tertiary + the container fills
 * are intentionally left to [CelestiaTheme]'s existing derivation step, which
 * recomputes them from the (now seeded) primary/secondary.
 */
fun seededCelestiaColors(base: CelestiaColors, seedArgb: Int, dark: Boolean): CelestiaColors {
    val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
    val m = MaterialDynamicColors()
    fun role(dc: DynamicColor): Color = Color(dc.getArgb(scheme))
    return base.copy(
        primary              = role(m.primary()),
        primaryVariant       = role(m.primary()).copy(alpha = 0.8f),
        secondary            = role(m.secondary()),
        background           = role(m.background()),
        surface              = role(m.surface()),
        surfaceVariant       = role(m.surfaceVariant()),
        surfaceContainerLow  = role(m.surfaceContainerLow()),
        surfaceContainer     = role(m.surfaceContainer()),
        surfaceContainerHigh = role(m.surfaceContainerHigh()),
        outline              = role(m.outline()),
        onPrimary            = role(m.onPrimary()),
        onSecondary          = role(m.onSecondary()),
        onBackground         = role(m.onBackground()),
        onSurface            = role(m.onSurface()),
        textPrimary          = role(m.onSurface()),
        textSecondary        = role(m.onSurfaceVariant()),
    )
}
