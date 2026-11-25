package hivens.ui.theme

import androidx.compose.runtime.Composable
import hivens.look.manager.api.LocalLookProvision
import hivens.look.manager.api.LookProvision
import hivens.look.preview.api.PreviewLook
import hivens.themes.api.Theme
import hivens.themes.api.ThemePreview

/**
 * [content] drawn in [theme], in its dark scheme or its light one by [dark]: the theme's answers provided
 * for the colour axis ([ThemePreview]), whatever the look around answers, and the bridge ([NxTheme]) set
 * from them. For a render of a component in one theme. [interfaceText] decides the interface face as it
 * does for [NxTheme], null takes the sample of the bridge around.
 *
 * Every other axis is drawn as the look around answers it, and where none is around as the [PreviewLook]
 * on the class path answers it, with decorative movement held still by [reduceMotion].
 */
@Composable
fun NxThemed(
    theme: Theme,
    dark: Boolean = true,
    interfaceText: String? = null,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val preview = if (LocalLookProvision.current === LookProvision.NONE) PreviewLook.found else null
    if (preview == null) {
        ThemePreview(theme, dark) { Bridged(interfaceText, content) }
    } else {
        preview.Provide(dark, reduceMotion) { ThemePreview(theme, dark) { Bridged(interfaceText, content) } }
    }
}

@Composable
private fun Bridged(interfaceText: String?, content: @Composable () -> Unit) =
    if (interfaceText == null) NxTheme(content = content) else NxTheme(interfaceText = interfaceText, content = content)
