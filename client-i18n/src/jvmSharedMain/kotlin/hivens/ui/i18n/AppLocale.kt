package hivens.ui.i18n

import androidx.compose.runtime.*
import kotlinx.serialization.Serializable

// ============================================================================
// Supported locales
// ============================================================================

@Serializable
enum class AppLocale(
    val displayName: String,
    val tag: String
) {
    RUSSIAN("Русский", "ru"),
    ENGLISH("English", "en"),
    GERMAN("Deutsch", "de"),

    /**
     * Carries its own alpha marker, in Latin so it reads whatever language the
     * picker is being read in. The translation was not written by a native
     * speaker, and the label is the place to say so: a warning in the list is
     * better than a surprise once the interface has switched.
     */
    JAPANESE("日本語 (alpha)", "ja");

    companion object {
        fun fromTag(tag: String): AppLocale =
            entries.firstOrNull { it.tag == tag } ?: RUSSIAN
    }
}

// ============================================================================
// String factory
// ============================================================================

fun stringsFor(locale: AppLocale): AppStrings = when (locale) {
    AppLocale.RUSSIAN -> RussianStrings
    AppLocale.ENGLISH -> EnglishStrings
    AppLocale.GERMAN  -> GermanStrings
    AppLocale.JAPANESE -> JapaneseStrings
}

// ============================================================================
// Compose CompositionLocal
// ============================================================================

val LocalStrings: ProvidableCompositionLocal<AppStrings> =
    staticCompositionLocalOf { RussianStrings }

/**
 * Convenience accessor inside Composables.
 *
 *   val s = LocalStrings.current
 *   Text(s.loginButton)
 */

// ============================================================================
// LocaleProvider -- wrap the whole app with this to propagate locale
// ============================================================================
//
// `LocalStrings` (above) is the single source of truth for UI text.
// Non-Composable lambdas inside Composables capture a live snapshot via
// `rememberUpdatedState(LocalStrings.current)` -- see `LaunchLogCollector`
// for the pattern.

@Composable
fun LocaleProvider(
    locale: AppLocale,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStrings provides stringsFor(locale),
        content = content,
    )
}
