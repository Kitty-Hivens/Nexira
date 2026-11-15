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
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.ui.theme.Theme
import hivens.ui.theme.Themes

/**
 * How the launcher looks right now, for the windows beside the main one.
 *
 * The theme, the scheme, the language and the customization are decided by the
 * main window's shell, where the settings that change them live. A window of the
 * application next to it, the console, is not inside that composition and inherits
 * none of it, so the shell writes what it decided here and the other window reads
 * it. Until the shell has written once, the scheme and the words are the ones
 * read before the boot.
 */
@Stable
class ShellLook(dark: Boolean, words: Translator) {
    var theme: Theme by mutableStateOf(Themes.default)
        private set
    var dark: Boolean by mutableStateOf(dark)
        private set
    var words: Translator by mutableStateOf(words)
        private set
    var uiFamily: FontFamily? by mutableStateOf(null)
        private set
    var customization: CustomizationSettings by mutableStateOf(CustomizationSettings())
        private set

    fun update(theme: Theme, dark: Boolean, words: Translator, uiFamily: FontFamily?, customization: CustomizationSettings) {
        this.theme = theme
        this.dark = dark
        this.words = words
        this.uiFamily = uiFamily
        this.customization = customization
    }
}

/**
 * Keeps [look] on what the main window draws with: the theme and the scheme from
 * [appearance], the words from [translations], and what only the shell knows,
 * [uiFamily] and [customization].
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
    translations: Translations,
    uiFamily: FontFamily?,
    customization: CustomizationSettings,
) {
    val theme = appearance.library.collectAsState().value.active
    val dark = appearance.dark.collectAsState().value
    val words = translations.current.collectAsState().value
    SideEffect { look.update(theme, dark, words, uiFamily, customization) }
}
