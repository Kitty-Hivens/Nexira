package hivens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.dejavu_sans
import hivens.ui.generated.resources.jetbrains_mono_bold
import hivens.ui.generated.resources.jetbrains_mono_medium
import hivens.ui.generated.resources.jetbrains_mono_regular
import hivens.ui.generated.resources.jetbrains_mono_semibold
import hivens.ui.generated.resources.roboto_flex_bold
import hivens.ui.generated.resources.roboto_flex_medium
import hivens.ui.generated.resources.roboto_flex_regular
import hivens.ui.generated.resources.roboto_flex_semibold
import org.jetbrains.compose.resources.Font

/**
 * Bundled type. Both families ship inside the app under the SIL Open Font
 * License, so the UI renders identically on every machine instead of inheriting
 * whatever sans the OS happens to default to (which is also how a non-free
 * system font would otherwise leak into the look).
 *
 * - Roboto Flex -- all UI text. Covers Latin, Cyrillic and Greek, so Russian
 *   and German render in the bundled face instead of falling back to a system
 *   font (Google Sans Flex was Latin-only). Its variable source is sliced to
 *   four static weights (400/500/600/700), subset to those scripts, at tooling
 *   time for predictable Skia rendering.
 * - JetBrains Mono -- code / hex / console, read through [LocalMonoFamily] so
 *   call sites swap the platform-generic monospace for the bundled one.
 */
@Composable
fun nexiraSansFamily(): FontFamily = FontFamily(
    Font(Res.font.roboto_flex_regular,  FontWeight.Normal),
    Font(Res.font.roboto_flex_medium,   FontWeight.Medium),
    Font(Res.font.roboto_flex_semibold, FontWeight.SemiBold),
    Font(Res.font.roboto_flex_bold,     FontWeight.Bold),
)

@Composable
fun nexiraMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular,  FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium,   FontWeight.Medium),
    Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(Res.font.jetbrains_mono_bold,     FontWeight.Bold),
)

/**
 * DejaVu Sans, bundled solely because it carries the full Braille block (U+2800)
 * at a uniform cell width -- JetBrains Mono has none, so Braille art would tofu or
 * fall back to a random system font. Used only for the decorative Braille console
 * filler; all braille cells share one advance, so picture art stays aligned.
 */
@Composable
fun nexiraBrailleFamily(): FontFamily = FontFamily(Font(Res.font.dejavu_sans))

/**
 * The bundled monospace family, provided by [CelestiaTheme]. Read this instead
 * of `FontFamily.Monospace` so code / hex / console use JetBrains Mono; the
 * default falls back to the platform monospace if a surface renders outside the
 * theme.
 */
val LocalMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** Material 3 type scale with every role re-pointed at [nexiraSansFamily]. */
@Composable
fun nexiraTypography(): Typography {
    val sans = nexiraSansFamily()
    return remember(sans) {
        with(Typography()) {
            copy(
                displayLarge   = displayLarge.copy(fontFamily = sans),
                displayMedium  = displayMedium.copy(fontFamily = sans),
                displaySmall   = displaySmall.copy(fontFamily = sans),
                headlineLarge  = headlineLarge.copy(fontFamily = sans),
                headlineMedium = headlineMedium.copy(fontFamily = sans),
                headlineSmall  = headlineSmall.copy(fontFamily = sans),
                titleLarge     = titleLarge.copy(fontFamily = sans),
                titleMedium    = titleMedium.copy(fontFamily = sans),
                titleSmall     = titleSmall.copy(fontFamily = sans),
                bodyLarge      = bodyLarge.copy(fontFamily = sans),
                bodyMedium     = bodyMedium.copy(fontFamily = sans),
                bodySmall      = bodySmall.copy(fontFamily = sans),
                labelLarge     = labelLarge.copy(fontFamily = sans),
                labelMedium    = labelMedium.copy(fontFamily = sans),
                labelSmall     = labelSmall.copy(fontFamily = sans),
            )
        }
    }
}
