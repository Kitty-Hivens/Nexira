package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.look.colour.api.Colour
import hivens.look.manager.api.LocalLookProvision
import hivens.look.manager.api.LookProvision
import hivens.look.preview.api.PreviewLook

/**
 * [content] drawn in [theme], in its dark scheme or its light one by [dark]: the theme's answers provided
 * for the colour axis, whatever the look around answers, and the bridge ([NxTheme]) set from them. For
 * what draws one theme on purpose, a preview of it or a render of a component in it. [interfaceText]
 * decides the interface face as it does for [NxTheme].
 *
 * Every other axis is drawn as the look around answers it, and where none is around, in a preview or a
 * test, as the [PreviewLook] there answers it, with decorative movement held still by [reduceMotion].
 */
@Composable
fun NxThemed(
    theme: Theme,
    dark: Boolean = true,
    interfaceText: String = LocalInterfaceText.current,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = theme.scheme(dark)
    val answers = remember(scheme) { scheme.colourAnswers() }
    val preview = if (LocalLookProvision.current === LookProvision.NONE) PreviewLook.found else null
    CompositionLocalProvider(Colour.local provides answers) {
        if (preview == null) {
            NxTheme(interfaceText = interfaceText, content = content)
        } else {
            preview.Provide(reduceMotion) { NxTheme(interfaceText = interfaceText, content = content) }
        }
    }
}
