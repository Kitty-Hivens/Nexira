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
    GERMAN("Deutsch", "de");

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
// `LocalStrings` (above) is the single source of truth for UI text. The B9
// cleanup (2026-05-17) removed the prior `object I18n` mutable-global escape
// hatch -- it had no thread-safety guarantees and was only needed because
// LauncherController (now in `client-launcher`, pre-B1) read i18n outside
// Compose. After B1 every consumer is @Composable; non-Composable lambdas
// inside them use `rememberUpdatedState(LocalStrings.current)` to capture
// a live snapshot. See `LaunchLogCollector` for the pattern.

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
