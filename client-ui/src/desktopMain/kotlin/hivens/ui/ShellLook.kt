package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import hivens.i18n.api.Translator
import hivens.ui.customization.CustomizationSettings
import hivens.widget.api.WidgetScope

/**
 * What the main window decided about how the launcher reads, for the windows beside it.
 *
 * The language, the interface face and the customization are decided by the main
 * window's shell, where the settings that change them live. A window of the
 * application next to it, the console, is not inside that composition and inherits
 * none of it, so the shell writes what it decided here and the other window reads
 * it. Until the shell has written once, the words are the ones the modules answered
 * before the boot. The look is not here: every window reads it off the app level
 * itself, through the look manager's one key.
 */
@Stable
class ShellLook(words: Translator) {
    var words: Translator by mutableStateOf(words)
        private set
    var uiFamily: FontFamily? by mutableStateOf(null)
        private set
    var customization: CustomizationSettings by mutableStateOf(CustomizationSettings())
        private set

    fun update(words: Translator, uiFamily: FontFamily?, customization: CustomizationSettings) {
        this.words = words
        this.uiFamily = uiFamily
        this.customization = customization
    }
}

/**
 * Keeps [look] on what the main window draws with: the words as [app] answers them
 * (see [ShellLookValues]), and what only the shell knows, [uiFamily] and
 * [customization].
 *
 * The words are read here, in a restart scope of its own, so a language switched
 * anywhere reaches the console at once. Published from the shell's scope, which did
 * not read them, a switch reached the console only once something unrelated happened
 * to recompose that scope.
 */
@Composable
internal fun PublishShellLook(
    look: ShellLook,
    app: WidgetScope,
    uiFamily: FontFamily?,
    customization: CustomizationSettings,
) {
    val words = app.read(ShellLookValues.Words)
    SideEffect { look.update(words, uiFamily, customization) }
}
