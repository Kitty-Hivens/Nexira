package hivens.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

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
    val outline: Color
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
    outline = Color(0xFF444444)
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
    outline = Color(0xFFCCCCCC)
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
    // If there is a custom theme, use it, otherwise use the default one
    val baseColors = if (useDarkTheme) DarkColorPalette else LightColorPalette

    val targetColors = if (customTheme != null) {
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
        glassAlpha = animatedGlassAlpha
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
