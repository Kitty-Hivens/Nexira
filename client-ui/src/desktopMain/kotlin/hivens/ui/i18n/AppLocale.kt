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

        /** Detect best match from the JVM default locale. */
        fun detectSystem(): AppLocale {
            val sysLanguage = System.getProperty("user.language") ?: return RUSSIAN
            return entries.firstOrNull { it.tag == sysLanguage } ?: RUSSIAN
        }
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
// Global singleton for non-Composable code (LauncherController, services, etc.)
// ============================================================================

/**
 * Use `I18n.s` anywhere outside Compose to access translated strings.
 *
 *   GameConsoleService.append(I18n.s.stateAuth, LogType.INFO)
 */
object I18n {
    private var _locale: AppLocale = AppLocale.detectSystem()
    private var _strings: AppStrings = stringsFor(_locale)

    val s: AppStrings get() = _strings

    fun setLocale(locale: AppLocale) {
        _locale = locale
        _strings = stringsFor(locale)
    }
}

// ============================================================================
// LocaleProvider — wrap the whole app with this to propagate locale
// ============================================================================

@Composable
fun LocaleProvider(
    locale: AppLocale,
    content: @Composable () -> Unit
) {
    // Keep I18n global in sync with Compose state
    SideEffect { I18n.setLocale(locale) }

    CompositionLocalProvider(
        LocalStrings provides stringsFor(locale),
        content = content
    )
}
