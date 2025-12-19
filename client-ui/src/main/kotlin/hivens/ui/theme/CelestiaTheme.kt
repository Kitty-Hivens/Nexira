package hivens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class CelestiaColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val primary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color = Color(0xFF03DAC6),
    val error: Color = Color(0xFFCF6679)
)

val LocalCelestiaColors = staticCompositionLocalOf { CelestiaDarkPalette }

// ТЕМНАЯ ТЕМА (Основная)
val CelestiaDarkPalette = CelestiaColors(
    isDark = true,
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF1A1A1A),
    border = Color(0xFF333333),
    primary = Color(0xFFBB86FC),
    textPrimary = Color(0xFFEEEEEE),
    textSecondary = Color(0xFF888888)
)

// TODO: Возможно для любителей экстрима ухудшить тему.
// СВЕТЛАЯ ТЕМА (Для любителей выжигать глаза)
val CelestiaLightPalette = CelestiaColors(
    isDark = false,
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE0E0E0),
    primary = Color(0xFF6200EE),
    textPrimary = Color(0xFF121212),
    textSecondary = Color(0xFF5F5F5F),
    error = Color(0xFFB00020)
)

@Composable
fun CelestiaTheme(
    useDarkTheme: Boolean = true, // Можно менять из настроек. Правда-правда!
    content: @Composable () -> Unit
) {
    val colors = if (useDarkTheme) CelestiaDarkPalette else CelestiaLightPalette

    val materialColors = if (useDarkTheme) {
        darkColors(
            primary = colors.primary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = if (useDarkTheme) Color.Black else Color.White,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
            error = colors.error
        )
    } else {
        lightColors(
            primary = colors.primary,
            background = colors.background,
            surface = colors.surface,
            onPrimary = Color.White,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
            error = colors.error
        )
    }

    val shapes = Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp)
    )

    CompositionLocalProvider(LocalCelestiaColors provides colors) {
        MaterialTheme(
            colors = materialColors,
            shapes = shapes,
            content = content
        )
    }
}

object CelestiaTheme {
    val colors: CelestiaColors
        @Composable
        get() = LocalCelestiaColors.current
}
