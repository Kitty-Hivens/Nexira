package hivens.ui.theme

import androidx.compose.runtime.Composable

/**
 * [content] as this module's previews are drawn: in no theme a person chooses, dark or light by [dark],
 * with decorative movement held still by [reduceMotion]. Every preview asks here and none says itself
 * what it is drawn in.
 */
@Composable
internal fun Previewed(dark: Boolean = true, reduceMotion: Boolean = false, content: @Composable () -> Unit) =
    NxThemed(NeutralTheme, dark = dark, reduceMotion = reduceMotion, content = content)
