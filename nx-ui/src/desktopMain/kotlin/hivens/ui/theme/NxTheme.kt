package hivens.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import hivens.ui.customization.LocalCustomization

// --- COLOR PALETTES ---
data class NxColors(
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val error: Color,
    val onPrimary: Color,
    val onSecondary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val glassBackground: Color,
    val glassAlpha: Float,
    val success: Color,
    val outline: Color,
    // Severity accents for notifications. `success` already doubles as
    // Severity.Success; the other three are new tokens so each accent can
    // drift per palette without dragging unrelated UI.
    val progressAccent: Color,
    val warnAccent: Color,
    val criticalAccent: Color,
    // Source/origin brand colors -- themeable so customization reaches the most
    // visible cards. The per-origin gradient + avatar shades are derived from
    // these in code (theme/BrandColors.kt), not stored as separate literals.
    val originSmartycraft: Color,
    val originMirror: Color,
    val originModrinth: Color,
    val originLocal: Color,
    // --- Material-3 tonal expansion ---
    // A third accent plus muted "container" fills and layered surface tiers, so UI
    // reads as more than primary-or-glass. Containers + [tertiary] are DERIVED per
    // theme from the resolved accents in the builder (so presets stay one-line and
    // any accent override propagates); the surface tiers come from the base palette.
    val tertiary: Color,
    val onTertiary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    // Decorative hash-assigned ramp for per-server / per-mod avatars: one themed
    // list the hash indexes into (unifies the old SERVER_PALETTES + AVATAR_PALETTE).
    // Not a semantic token and not customization-overridable -- just stable,
    // in-theme variety; the dark and light presets carry their own ramp.
    val decorativeRamp: List<Color>,
)

internal val DarkColorPalette = NxColors(
    primary = Color(0xFFBB86FC), // Soft purple
    primaryVariant = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212), // Almost black, but not #000
    surface = Color(0xFF1E1E1E), // A little lighter for cards
    surfaceVariant = Color(0xFF2C2C2C),
    error = Color(0xFFCF6679),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    textPrimary = Color(0xFFEEEEEE),
    textSecondary = Color(0xFFB0B0B0),
    glassBackground = Color(0xFF000000), // Base for glass
    glassAlpha = 0.60f, // Glass transparency (dark)
    success = Color(0xFF4CAF50),
    outline = Color(0xFF444444),
    progressAccent = Color(0xFF6A84FF),
    warnAccent     = Color(0xFFE0B341),
    criticalAccent = Color(0xFFD8484A),
    originSmartycraft = Color(0xFF8B5CF6), // violet
    originMirror      = Color(0xFF3B82F6), // blue
    originModrinth    = Color(0xFF22C55E), // green
    originLocal       = Color(0xFF9CA3AF), // grey
    // Tonal expansion -- placeholders; the builder re-derives containers + tertiary
    // from the live accents. Surface tiers below are the real dark-ground values.
    tertiary = Color(0xFFF0A66B),
    onTertiary = Color.Black,
    primaryContainer = Color(0xFF332A4A),
    onPrimaryContainer = Color(0xFFE6DBFF),
    secondaryContainer = Color(0xFF14352F),
    onSecondaryContainer = Color(0xFFB9F5EA),
    tertiaryContainer = Color(0xFF3A2E20),
    onTertiaryContainer = Color(0xFFFFE2C7),
    surfaceContainerLow  = Color(0xFF181818),
    surfaceContainer     = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    decorativeRamp = listOf(
        Color(0xFF7C3AED), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
        Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFF97316), Color(0xFF6366F1),
    ),
)

internal val LightColorPalette = NxColors(
    primary = Color(0xFF5E68C0),       // Soft indigo (instead of harsh purple)
    primaryVariant = Color(0xFF3F51B5),
    secondary = Color(0xFF26A69A),     // Calm teal
    background = Color(0xFFF5F7FA),    // Very light gray (not white!)
    surface = Color(0xFFFFFFFF),       // White cards
    surfaceVariant = Color(0xFFE8EAF0),
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    textPrimary = Color(0xFF263238),   // Dark blue-gray (softer than black)
    textSecondary = Color(0xFF78909C), // Gray-blue text
    glassBackground = Color(0xFFFFFFFF),
    glassAlpha = 0.65f,                // Slightly more opacity for readability
    success = Color(0xFF66BB6A),
    outline = Color(0xFFCCCCCC),
    progressAccent = Color(0xFF3C5BD9),
    warnAccent     = Color(0xFFB37A0E),
    criticalAccent = Color(0xFFB3262A),
    originSmartycraft = Color(0xFF6D28D9), // deeper violet for light bg
    originMirror      = Color(0xFF2563EB), // deeper blue
    originModrinth    = Color(0xFF16A34A), // deeper green
    originLocal       = Color(0xFF64748B), // slate
    // Tonal expansion -- placeholders re-derived by the builder; surface tiers real.
    tertiary = Color(0xFFB5651D),
    onTertiary = Color.White,
    primaryContainer = Color(0xFFE3E0F7),
    onPrimaryContainer = Color(0xFF1A1740),
    secondaryContainer = Color(0xFFD6F0EC),
    onSecondaryContainer = Color(0xFF0A3B33),
    tertiaryContainer = Color(0xFFF6E2CE),
    onTertiaryContainer = Color(0xFF4A2A09),
    surfaceContainerLow  = Color(0xFFF0F2F6),
    surfaceContainer     = Color(0xFFEAECF2),
    surfaceContainerHigh = Color(0xFFE2E5EC),
    decorativeRamp = listOf(
        Color(0xFF6D28D9), Color(0xFF0284C7), Color(0xFF059669), Color(0xFFD97706),
        Color(0xFFDB2777), Color(0xFF0D9488), Color(0xFFEA580C), Color(0xFF4F46E5),
    ),
)

val LocalNxColors = staticCompositionLocalOf<NxColors> {
    error("No NxColors provided")
}

// --- THEME WITH ANIMATION AND SUPPORT FOR CUSTOM THEMES ---

@Composable
fun NxTheme(
    useDarkTheme: Boolean = true,
    customTheme: CustomTheme? = null,
    style: StyleSpec = CelestiaStyle,
    // Material You: when [paletteFromWallpaper] is on and a [paletteSeed] (ARGB,
    // extracted from the wallpaper) is available, the base palette is generated from
    // it -- tinted tonal surfaces seeded by the background. Otherwise the fixed
    // Celestia palette. Defaulted so other call sites (the console window) are unaffected.
    paletteSeed: Int? = null,
    paletteFromWallpaper: Boolean = false,
    content: @Composable () -> Unit
) {
    val customization = LocalCustomization.current

    // Base palette: fixed Celestia, or wallpaper-seeded (Monet) when enabled.
    val rawBase = if (useDarkTheme) DarkColorPalette else LightColorPalette
    val baseColors = if (paletteFromWallpaper && paletteSeed != null)
        seededNxColors(rawBase, paletteSeed, useDarkTheme) else rawBase

    val themedColors = if (customTheme != null) {
        baseColors.copy(
            primary        = CustomTheme.parseHexColor(customTheme.primary),
            primaryVariant = CustomTheme.parseHexColor(customTheme.primary).copy(alpha = 0.8f),
            secondary      = CustomTheme.parseHexColor(customTheme.secondary),
            success        = CustomTheme.parseHexColor(customTheme.success),
            error          = CustomTheme.parseHexColor(customTheme.error),
        )
    } else {
        baseColors
    }

    // The accent override lands on top of the active theme, re-seeding the
    // primary accent.
    val targetColors = run {
        var c = themedColors
        customization.accentOverride?.let { hex ->
            parseHexColorOrNull(hex)?.let { col ->
                c = c.copy(primary = col, primaryVariant = col.copy(alpha = 0.8f))
            }
        }
        // Derive the tonal expansion from the resolved accents (after theme +
        // accent + experimental overrides), so containers and the third accent
        // always track the live primary/secondary. Surface tiers stay from base.
        val tert = deriveTertiary(c.primary, c.secondary)
        c = c.copy(
            tertiary             = tert,
            onTertiary           = contentOn(tert),
            primaryContainer     = tonalContainer(c.primary, c.surface, useDarkTheme),
            onPrimaryContainer   = onTonalContainer(c.primary, useDarkTheme),
            secondaryContainer   = tonalContainer(c.secondary, c.surface, useDarkTheme),
            onSecondaryContainer = onTonalContainer(c.secondary, useDarkTheme),
            tertiaryContainer    = tonalContainer(tert, c.surface, useDarkTheme),
            onTertiaryContainer  = onTonalContainer(tert, useDarkTheme),
        )
        c
    }

    // Color change animation duration scales with style.animationMultiplier.
    // Brut (multiplier 0) collapses to ~1ms so palette swaps land instantly;
    // Celestia keeps the original 500ms cross-fade.
    val animDurationMs = style.animationDurationMs(500)
    val colorAnimSpec = remember(animDurationMs) { TweenSpec<Color>(durationMillis = animDurationMs) }

    val animatedPrimary by animateColorAsState(targetColors.primary, colorAnimSpec)
    val animatedSecondary by animateColorAsState(targetColors.secondary, colorAnimSpec)
    val animatedBackground by animateColorAsState(targetColors.background, colorAnimSpec)
    val animatedSurface by animateColorAsState(targetColors.surface, colorAnimSpec)
    val animatedSurfaceVariant by animateColorAsState(targetColors.surfaceVariant, colorAnimSpec)
    val animatedError by animateColorAsState(targetColors.error, colorAnimSpec)
    val animatedSuccess by animateColorAsState(targetColors.success, colorAnimSpec)
    val animatedTextPrimary by animateColorAsState(targetColors.textPrimary, colorAnimSpec)
    val animatedTextSecondary by animateColorAsState(targetColors.textSecondary, colorAnimSpec)
    val animatedOutline by animateColorAsState(targetColors.outline, colorAnimSpec)
    val animatedGlassBg by animateColorAsState(targetColors.glassBackground, colorAnimSpec)
    val animatedGlassAlpha by animateFloatAsState(targetColors.glassAlpha, TweenSpec(animDurationMs))
    val animatedProgressAccent by animateColorAsState(targetColors.progressAccent, colorAnimSpec)
    val animatedWarnAccent by animateColorAsState(targetColors.warnAccent, colorAnimSpec)
    val animatedCriticalAccent by animateColorAsState(targetColors.criticalAccent, colorAnimSpec)
    val animatedTertiary by animateColorAsState(targetColors.tertiary, colorAnimSpec)
    val animatedPrimaryContainer by animateColorAsState(targetColors.primaryContainer, colorAnimSpec)
    val animatedSecondaryContainer by animateColorAsState(targetColors.secondaryContainer, colorAnimSpec)
    val animatedTertiaryContainer by animateColorAsState(targetColors.tertiaryContainer, colorAnimSpec)
    val animatedSurfaceContainerLow by animateColorAsState(targetColors.surfaceContainerLow, colorAnimSpec)
    val animatedSurfaceContainer by animateColorAsState(targetColors.surfaceContainer, colorAnimSpec)
    val animatedSurfaceContainerHigh by animateColorAsState(targetColors.surfaceContainerHigh, colorAnimSpec)

    // Assembling an animated palette
    val animatedPalette = targetColors.copy(
        primary = animatedPrimary,
        secondary = animatedSecondary,
        background = animatedBackground,
        surface = animatedSurface,
        surfaceVariant = animatedSurfaceVariant,
        error = animatedError,
        success = animatedSuccess,
        textPrimary = animatedTextPrimary,
        textSecondary = animatedTextSecondary,
        outline = animatedOutline,
        glassBackground = animatedGlassBg,
        glassAlpha = animatedGlassAlpha,
        progressAccent = animatedProgressAccent,
        warnAccent = animatedWarnAccent,
        criticalAccent = animatedCriticalAccent,
        tertiary = animatedTertiary,
        primaryContainer = animatedPrimaryContainer,
        secondaryContainer = animatedSecondaryContainer,
        tertiaryContainer = animatedTertiaryContainer,
        surfaceContainerLow = animatedSurfaceContainerLow,
        surfaceContainer = animatedSurfaceContainer,
        surfaceContainerHigh = animatedSurfaceContainerHigh,
    )

    // M3 ColorScheme
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = animatedPalette.primary,
            secondary = animatedPalette.secondary,
            background = animatedPalette.background,
            surface = animatedPalette.surface,
            surfaceVariant = animatedPalette.surfaceVariant,
            error = animatedPalette.error,
            onPrimary = animatedPalette.onPrimary,
            onSecondary = animatedPalette.onSecondary,
            onBackground = animatedPalette.onBackground,
            onSurface = animatedPalette.onSurface,
            outline = animatedPalette.outline
        )
    } else {
        lightColorScheme(
            primary = animatedPalette.primary,
            secondary = animatedPalette.secondary,
            background = animatedPalette.background,
            surface = animatedPalette.surface,
            surfaceVariant = animatedPalette.surfaceVariant,
            error = animatedPalette.error,
            onPrimary = animatedPalette.onPrimary,
            onSecondary = animatedPalette.onSecondary,
            onBackground = animatedPalette.onBackground,
            onSurface = animatedPalette.onSurface,
            outline = animatedPalette.outline
        )
    }

    // Provide LocalStyle alongside the palette so child composables can
    // read StyleSpec tokens without a separate CompositionLocalProvider
    // chain at every entry point.
    CompositionLocalProvider(
        LocalNxColors provides animatedPalette,
        LocalStyle          provides style,
        LocalMonoFamily     provides nexiraMonoFamily(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = style.toMaterialShapes(),
            typography  = nexiraTypography(),
            content     = content
        )
    }
}

// Easy access via NxTheme.colors
object NxTheme {
    val colors: NxColors
        @Composable
        get() = LocalNxColors.current
}

private fun parseHexColorOrNull(hex: String): Color? = try {
    val clean = hex.trim().removePrefix("#")
    val full = if (clean.length == 6) "FF$clean" else clean
    Color(full.toLong(16))
} catch (_: Exception) { null }

// --- Tonal derivation for the Material-3 expansion ---
// container = a muted fill of the accent (dark: pulled toward the surface; light:
// toward white). onContainer = a readable tone of the accent for content on that
// fill. [deriveTertiary] is a third hue between the two accents, so it always
// stays inside the active theme's family instead of clashing with it.
private fun tonalContainer(accent: Color, surface: Color, dark: Boolean): Color =
    if (dark) lerp(accent, surface, 0.70f) else lerp(accent, Color.White, 0.82f)

private fun onTonalContainer(accent: Color, dark: Boolean): Color =
    if (dark) lerp(accent, Color.White, 0.74f) else lerp(accent, Color.Black, 0.58f)

private fun contentOn(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

private fun deriveTertiary(primary: Color, secondary: Color): Color = lerp(secondary, primary, 0.5f)
