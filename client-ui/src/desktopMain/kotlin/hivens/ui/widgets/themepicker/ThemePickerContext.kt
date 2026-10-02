package hivens.ui.widgets.themepicker

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.theme.Theme
import hivens.ui.theme.Themes

// Surface-scoped state the theme-picker widgets share. The grid shows every theme
// and writes `selected` on a click. The preview reads `selected` and draws it.
// Applying is not here: the button is surface chrome, not a widget, so it calls the
// surface's own callback.
//
// [isDark] is the mode the launcher is in, so each theme is shown the way it would
// actually be drawn: a theme with no light scheme stays dark even when the launcher
// is light, and says so.
//
// Stub used by EditorSurfaceHost when a foreign-surface widget gets dropped into
// theme.picker: no themes, nothing to apply. Plain class, not data class: it holds a
// MutableState reference and a lambda, and generated value semantics would mislead.
class ThemePickerContext(
    val themes: List<Theme>,
    val selected: MutableState<Theme>,
    val isDark: Boolean,
    val onBack: () -> Unit,
)

val LocalThemePickerContext: ProvidableCompositionLocal<ThemePickerContext> =
    staticCompositionLocalOf {
        error("LocalThemePickerContext not provided -- render inside ThemePickerSurface")
    }

internal val STUB_THEME_PICKER: ThemePickerContext = ThemePickerContext(
    themes   = emptyList(),
    selected = mutableStateOf(Themes.default),
    isDark   = true,
    onBack   = {},
)
