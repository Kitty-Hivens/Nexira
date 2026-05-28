package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import hivens.ui.background.BackgroundSettings

// Surface-scoped state the bg-settings widgets share. `settings` is
// the live edit buffer; the `update` lambda commits one field-change
// into the surface's persistence callback. previewMousePos +
// previewSize are written by the preview widget on pointer-move so
// the parallax effect tracks the cursor live. Plain class -- holds
// MutableState references, so generated equals would be misleading.
class BgSettingsContext(
    val settings: MutableState<BackgroundSettings>,
    val update: (BackgroundSettings.() -> BackgroundSettings) -> Unit,
    val previewMousePos: MutableState<Offset>,
    val previewSize: MutableState<IntSize>,
)

val LocalBgSettingsContext: ProvidableCompositionLocal<BgSettingsContext> =
    staticCompositionLocalOf {
        error("LocalBgSettingsContext not provided -- render inside BgSettingsSurface")
    }

internal val STUB_BG_SETTINGS: BgSettingsContext = BgSettingsContext(
    settings        = mutableStateOf(BackgroundSettings()),
    update          = {},
    previewMousePos = mutableStateOf(Offset(0.5f, 0.5f)),
    previewSize     = mutableStateOf(IntSize.Zero),
)
