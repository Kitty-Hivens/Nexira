package hivens.ui.theme

import androidx.compose.runtime.Composable
import hivens.look.preview.api.PreviewLook

/**
 * [content] as this module's previews are drawn: dark or light by [dark], with decorative movement held
 * still by [reduceMotion], as the [PreviewLook] on the class path answers and in the axes' base answers
 * where there is none. Every preview asks here and none says itself what it is drawn in.
 */
@Composable
internal fun Previewed(dark: Boolean = true, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val preview = PreviewLook.found
    if (preview == null) NxTheme(content = content) else preview.Provide(dark, reduceMotion) { NxTheme(content = content) }
}
