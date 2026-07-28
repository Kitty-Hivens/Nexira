package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import kotlin.math.roundToInt

/**
 * Material You colour science -> [NxColors] (Monet). Given a [PaletteSpec], build a
 * tonal scheme and map its roles onto [base], replacing the palette-derived fields
 * -- accents, surfaces, outline, on-colours, severity -- while keeping the brand
 * tokens (origin*, decorativeRamp, glass) from [base]. The surface tiers come out
 * tinted toward the seed at their elevation, so two planes differ by COLOUR, not
 * just lightness, which is the whole point (same-on-same never reads as separate).
 *
 * Pure: no Compose state, no IO -- unit-testable.
 *
 * [NxColors.tertiary] and the container fills are written here but recomputed by
 * [NxTheme] from the resolved primary/secondary, so a two-tone spec's tertiary does
 * not reach the screen until that derivation step goes away with the preset system.
 */
fun generatedNxColors(base: NxColors, spec: PaletteSpec): NxColors {
    val scheme = spec.toScheme()
    val m = MaterialDynamicColors()
    fun role(dc: DynamicColor): Color = Color(dc.getArgb(scheme))

    // Severity accents are not Material roles -- the spec has `error` and nothing
    // for success, warning or progress -- so they are built here from fixed hues,
    // borrowing BOTH the tone and the chroma the scheme gave error. The tone is what
    // makes them follow dark/light and the contrast level without a second contrast
    // solver. The chroma is borrowed rather than fixed because the 2025 spec scales
    // error with how expressive the scheme is -- roughly 29 on a monochrome scheme
    // against 60 on a colourful one -- and a fixed value would leave a success
    // louder than the error beside it wherever the scheme is quiet. Only the hues
    // stay constant, because severity is a learned code and not a decorative choice.
    val errorArgb = m.error().getArgb(scheme)
    val errorHct = Hct.fromInt(errorArgb)
    val severityTone = errorHct.tone.roundToInt()
    val severityChroma = errorHct.chroma
    fun severity(hue: Double): Color =
        Color(TonalPalette.fromHueAndChroma(hue, severityChroma).tone(severityTone))

    return base.copy(
        primary              = role(m.primary()),
        primaryVariant       = role(m.primary()).copy(alpha = 0.8f),
        secondary            = role(m.secondary()),
        tertiary             = role(m.tertiary()),
        onTertiary           = role(m.onTertiary()),
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
        error                = Color(errorArgb),
        criticalAccent       = Color(errorArgb),
        success              = severity(SUCCESS_HUE),
        warnAccent           = severity(WARN_HUE),
        progressAccent       = severity(PROGRESS_HUE),
    )
}

/** Single-seed shorthand: the default variant at standard contrast. */
fun seededNxColors(base: NxColors, seedArgb: Int, dark: Boolean): NxColors =
    generatedNxColors(base, PaletteSpec(seedArgb = seedArgb, dark = dark))

// Severity hues in HCT degrees: green, amber, blue. Far enough apart that the three
// stay separable at one tone, and none of them lands on the red the error role owns.
private const val SUCCESS_HUE = 145.0
private const val WARN_HUE = 75.0
private const val PROGRESS_HUE = 255.0
