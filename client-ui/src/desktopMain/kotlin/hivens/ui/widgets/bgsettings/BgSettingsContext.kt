package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.background.BackgroundSettings

// Surface-scoped state the bg-settings widgets share. `settings` is the live edit
// buffer; the `update` lambda commits one field-change into the surface's
// persistence callback. Plain class -- holds a MutableState reference, so generated
// equals would be misleading.
class BgSettingsContext(
    val settings: MutableState<BackgroundSettings>,
    val update: (BackgroundSettings.() -> BackgroundSettings) -> Unit,
)

val LocalBgSettingsContext: ProvidableCompositionLocal<BgSettingsContext> =
    staticCompositionLocalOf {
        error("LocalBgSettingsContext not provided -- render inside BgSettingsSurface")
    }

internal val STUB_BG_SETTINGS: BgSettingsContext = BgSettingsContext(
    settings = mutableStateOf(BackgroundSettings()),
    update   = {},
)
