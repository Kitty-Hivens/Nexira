package hivens.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import hivens.ui.customization.ColorRole
import hivens.ui.customization.LocalCustomization

// --- COLOR PALETTES ---
data class CelestiaColors(
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
    // Decorative hash-assigned ramp for per-server / per-mod avatars: one themed
    // list the hash indexes into (unifies the old SERVER_PALETTES + AVATAR_PALETTE).
    // Not a semantic token and not customization-overridable -- just stable,
    // in-theme variety; the dark and light presets carry their own ramp.
    val decorativeRamp: List<Color>,
)

private val DarkColorPalette = CelestiaColors(
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
    decorativeRamp = listOf(
        Color(0xFF7C3AED), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
        Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFF97316), Color(0xFF6366F1),
    ),
)

private val LightColorPalette = CelestiaColors(
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
    decorativeRamp = listOf(
        Color(0xFF6D28D9), Color(0xFF0284C7), Color(0xFF059669), Color(0xFFD97706),
        Color(0xFFDB2777), Color(0xFF0D9488), Color(0xFFEA580C), Color(0xFF4F46E5),
    ),
)

val LocalCelestiaColors = staticCompositionLocalOf<CelestiaColors> {
    error("No CelestiaColors provided")
}

// --- THEME WITH ANIMATION AND SUPPORT FOR CUSTOM THEMES ---

@Composable
fun CelestiaTheme(
    useDarkTheme: Boolean = true,
    customTheme: CustomTheme? = null,
    style: StyleSpec = CelestiaStyle,
    content: @Composable () -> Unit
) {
    val customization = LocalCustomization.current

    // If there is a custom theme, use it, otherwise use the default one
    val baseColors = if (useDarkTheme) DarkColorPalette else LightColorPalette

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

    // Customization overrides land on top of the active theme. Accent
    // always available; per-role full overrides only when the user has
    // explicitly enabled the experimental toggle.
    val targetColors = run {
        var c = themedColors
        customization.accentOverride?.let { hex ->
            parseHexColorOrNull(hex)?.let { col ->
                c = c.copy(primary = col, primaryVariant = col.copy(alpha = 0.8f))
            }
        }
        if (customization.experimentalColorOverridesEnabled && customization.colorOverrides.isNotEmpty()) {
            val o = customization.colorOverrides
            c = c.copy(
                primary        = o[ColorRole.PRIMARY]?.let(::parseHexColorOrNull)        ?: c.primary,
                secondary      = o[ColorRole.SECONDARY]?.let(::parseHexColorOrNull)      ?: c.secondary,
                background     = o[ColorRole.BACKGROUND]?.let(::parseHexColorOrNull)     ?: c.background,
                surface        = o[ColorRole.SURFACE]?.let(::parseHexColorOrNull)        ?: c.surface,
                success        = o[ColorRole.SUCCESS]?.let(::parseHexColorOrNull)        ?: c.success,
                error          = o[ColorRole.ERROR]?.let(::parseHexColorOrNull)          ?: c.error,
                outline        = o[ColorRole.OUTLINE]?.let(::parseHexColorOrNull)        ?: c.outline,
                // editor-4 additions
                textPrimary    = o[ColorRole.TEXT_PRIMARY]?.let(::parseHexColorOrNull)   ?: c.textPrimary,
                textSecondary  = o[ColorRole.TEXT_SECONDARY]?.let(::parseHexColorOrNull) ?: c.textSecondary,
                progressAccent = o[ColorRole.PROGRESS_ACCENT]?.let(::parseHexColorOrNull) ?: c.progressAccent,
                warnAccent     = o[ColorRole.WARN_ACCENT]?.let(::parseHexColorOrNull)    ?: c.warnAccent,
                criticalAccent = o[ColorRole.CRITICAL_ACCENT]?.let(::parseHexColorOrNull) ?: c.criticalAccent,
                // GLASS_ALPHA stored as plain "0.55" string in the
                // overrides map; parse to Float and clamp.
                glassAlpha     = o[ColorRole.GLASS_ALPHA]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: c.glassAlpha,
                originSmartycraft = o[ColorRole.ORIGIN_SMARTYCRAFT]?.let(::parseHexColorOrNull) ?: c.originSmartycraft,
                originMirror      = o[ColorRole.ORIGIN_MIRROR]?.let(::parseHexColorOrNull)      ?: c.originMirror,
                originModrinth    = o[ColorRole.ORIGIN_MODRINTH]?.let(::parseHexColorOrNull)    ?: c.originModrinth,
                originLocal       = o[ColorRole.ORIGIN_LOCAL]?.let(::parseHexColorOrNull)       ?: c.originLocal,
            )
        }
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
        LocalCelestiaColors provides animatedPalette,
        LocalStyle          provides style,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = style.toMaterialShapes(),
            content     = content
        )
    }
}

// Easy access via CelestiaTheme.colors
object CelestiaTheme {
    val colors: CelestiaColors
        @Composable
        get() = LocalCelestiaColors.current
}

private fun parseHexColorOrNull(hex: String): Color? = try {
    val clean = hex.trim().removePrefix("#")
    val full = if (clean.length == 6) "FF$clean" else clean
    Color(full.toLong(16))
} catch (_: Exception) { null }
