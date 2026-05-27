package hivens.ui.widgets.themepicker

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemePresets

// Surface-scoped state + callbacks the theme-picker widgets share.
// The grid widget reads `themes` to render every preset and writes
// `selectedTheme.value` on tap; the preview widget reads
// `selectedTheme.value` to render the preview panel. The apply
// button (still in ThemePickerSurface chrome, not a widget) invokes
// `onApply(selectedTheme.value)`.
//
// Stub used by EditorSurfaceHost when a foreign-surface widget gets
// dropped into theme.picker -- callbacks no-op rather than crash.
// Plain class, not data class -- holds a MutableState reference and
// two lambdas, all reference-equality fields. Generated equals /
// hashCode / toString would be misleading "value semantics" the
// holder does not actually provide.
class ThemePickerContext(
    val themes: List<CustomTheme>,
    val selectedTheme: MutableState<CustomTheme>,
    val onApply: (CustomTheme) -> Unit,
    val onBack: () -> Unit,
)

val LocalThemePickerContext: ProvidableCompositionLocal<ThemePickerContext> =
    staticCompositionLocalOf {
        error("LocalThemePickerContext not provided -- render inside ThemePickerSurface")
    }

internal val STUB_THEME_PICKER: ThemePickerContext = ThemePickerContext(
    themes        = emptyList(),
    selectedTheme = mutableStateOf(ThemePresets.getAll().first()),
    onApply       = {},
    onBack        = {},
)
