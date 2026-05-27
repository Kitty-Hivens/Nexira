package hivens.ui.widgets.customization

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.customization.CustomizationSettings

// Surface-scoped state the customization widgets share. settings is
// the live edit buffer; update commits a copy into the surface's
// persistence callback. Plain class -- holds MutableState reference.
class CustomizationContext(
    val settings: MutableState<CustomizationSettings>,
    val update: (CustomizationSettings.() -> CustomizationSettings) -> Unit,
)

val LocalCustomizationContext: ProvidableCompositionLocal<CustomizationContext> =
    staticCompositionLocalOf {
        error("LocalCustomizationContext not provided -- render inside CustomizationSurface")
    }

internal val STUB_CUSTOMIZATION: CustomizationContext = CustomizationContext(
    settings = mutableStateOf(CustomizationSettings()),
    update   = {},
)
