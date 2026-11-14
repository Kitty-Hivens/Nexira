package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import hivens.ui.appearance.AppearanceStore
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.theme.Theme
import hivens.ui.theme.Themes

/**
 * How the launcher looks right now, for the windows beside the main one.
 *
 * The theme, the scheme, the language and the customization are decided by the
 * main window's shell, where the settings that change them live. A window of the
 * application next to it, the console, is not inside that composition and inherits
 * none of it, so the shell writes what it decided here and the other window reads
 * it. Until the shell has written once, the scheme and the language are the ones
 * read before the boot.
 */
@Stable
class ShellLook(dark: Boolean, locale: AppLocale) {
    var theme: Theme by mutableStateOf(Themes.default)
        private set
    var dark: Boolean by mutableStateOf(dark)
        private set
    var locale: AppLocale by mutableStateOf(locale)
        private set
    var uiFamily: FontFamily? by mutableStateOf(null)
        private set
    var customization: CustomizationSettings by mutableStateOf(CustomizationSettings())
        private set

    fun update(theme: Theme, dark: Boolean, locale: AppLocale, uiFamily: FontFamily?, customization: CustomizationSettings) {
        this.theme = theme
        this.dark = dark
        this.locale = locale
        this.uiFamily = uiFamily
        this.customization = customization
    }
}

/**
 * Keeps [look] on what the main window draws with: the theme, the scheme and the
 * language from [appearance], and what only the shell knows, [uiFamily] and
 * [customization].
 *
 * The appearance is read here, in a restart scope of its own, so a theme or a dark
 * scheme switched anywhere reaches the console at once. Published from the shell's
 * scope, which did not read them, a switch reached the console only once something
 * unrelated happened to recompose that scope.
 */
@Composable
internal fun PublishShellLook(
    look: ShellLook,
    appearance: AppearanceStore,
    uiFamily: FontFamily?,
    customization: CustomizationSettings,
) {
    val theme = appearance.library.collectAsState().value.active
    val dark = appearance.dark.collectAsState().value
    val locale = appearance.locale.collectAsState().value
    SideEffect { look.update(theme, dark, locale, uiFamily, customization) }
}
