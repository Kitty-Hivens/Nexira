package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.background.BackgroundSettings

// Surface-scoped state the bg-settings widgets share. `settings` is the shell's
// own value, read through, and the `update` lambda applies one field-change to it.
// Plain class -- holds a State reference, so generated equals would be misleading.
class BgSettingsContext(
    val settings: State<BackgroundSettings>,
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
