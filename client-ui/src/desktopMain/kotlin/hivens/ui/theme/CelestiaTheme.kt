package hivens.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// --- ЦВЕТОВЫЕ ПАЛИТРЫ ---

private val DarkColorPalette = CelestiaColors(
    primary = Color(0xFFBB86FC),       // Мягкий фиолетовый
    primaryVariant = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212),    // Почти черный, но не #000
    surface = Color(0xFF1E1E1E),       // Чуть светлее для карточек
    error = Color(0xFFCF6679),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    textPrimary = Color(0xFFEEEEEE),
    textSecondary = Color(0xFFB0B0B0),
    glassBackground = Color(0xFF000000), // Основа для стекла
    glassAlpha = 0.60f,                  // Прозрачность стекла (темное)
    success = Color(0xFF4CAF50)
)

private val LightColorPalette = CelestiaColors(
    primary = Color(0xFF5E68C0),       // Мягкий индиго (вместо резкого фиолетового)
    primaryVariant = Color(0xFF3F51B5),
    secondary = Color(0xFF26A69A),     // Спокойный тил
    background = Color(0xFFF5F7FA),    // Очень светло-серый (не белый!)
    surface = Color(0xFFFFFFFF),       // Карточки белые
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    textPrimary = Color(0xFF263238),   // Темно-синий-серый (мягче черного)
    textSecondary = Color(0xFF78909C), // Серо-голубой текст
    glassBackground = Color(0xFFFFFFFF),
    glassAlpha = 0.65f,                // Чуть больше непрозрачности для читаемости
    success = Color(0xFF66BB6A)
)

// --- КЛАСС ЦВЕТОВ ---
// Добавляем свои поля, которых нет в стандартном MaterialTheme
data class CelestiaColors(
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val error: Color,
    val onPrimary: Color,
    val onSecondary: Color,
    val onBackground: Color,
    val onSurface: Color,

    // Кастомные цвета
    val textPrimary: Color,
    val textSecondary: Color,
    val glassBackground: Color,
    val glassAlpha: Float,
    val success: Color
)

// Глобальный CompositionLocal для доступа к цветам
val LocalCelestiaColors = staticCompositionLocalOf<CelestiaColors> {
    error("No CelestiaColors provided")
}

// --- ТЕМА С АНИМАЦИЕЙ ---

@Composable
fun CelestiaTheme(
    useDarkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val targetColors = if (useDarkTheme) DarkColorPalette else LightColorPalette

    // Анимация смены цветов (500мс)
    val animSpec = remember { TweenSpec<Color>(durationMillis = 500) }

    val animatedPrimary by animateColorAsState(targetColors.primary, animSpec)
    val animatedBackground by animateColorAsState(targetColors.background, animSpec)
    val animatedSurface by animateColorAsState(targetColors.surface, animSpec)
    val animatedTextPrimary by animateColorAsState(targetColors.textPrimary, animSpec)
    val animatedTextSecondary by animateColorAsState(targetColors.textSecondary, animSpec)
    val animatedGlassBg by animateColorAsState(targetColors.glassBackground, animSpec)

    // Alpha анимируем отдельно, так как это Float
    val animatedGlassAlpha by animateFloatAsState(targetColors.glassAlpha, TweenSpec(500))

    // Собираем анимированную палитру
    val animatedPalette = targetColors.copy(
        primary = animatedPrimary,
        background = animatedBackground,
        surface = animatedSurface,
        textPrimary = animatedTextPrimary,
        textSecondary = animatedTextSecondary,
        glassBackground = animatedGlassBg,
        glassAlpha = animatedGlassAlpha
    )

    // Конвертируем в Material Colors для совместимости со стандартными компонентами
    val materialColors = if (useDarkTheme) {
        darkColors(
            primary = animatedPalette.primary,
            surface = animatedPalette.surface,
            background = animatedPalette.background,
            onSurface = animatedPalette.onSurface
        )
    } else {
        lightColors(
            primary = animatedPalette.primary,
            surface = animatedPalette.surface,
            background = animatedPalette.background,
            onSurface = animatedPalette.onSurface
        )
    }

    CompositionLocalProvider(
        LocalCelestiaColors provides animatedPalette
    ) {
        MaterialTheme(
            colors = materialColors,
            shapes = MaterialTheme.shapes, // Можно кастомизировать шейпы тут
            content = content
        )
    }
}

// Удобный доступ через CelestiaTheme.colors
object CelestiaTheme {
    val colors: CelestiaColors
        @Composable
        get() = LocalCelestiaColors.current
}
