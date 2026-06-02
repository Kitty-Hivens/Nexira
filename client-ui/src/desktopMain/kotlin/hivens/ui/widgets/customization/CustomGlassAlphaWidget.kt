package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.customization.ColorRole
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Glass alpha is stored in colorOverrides under the GLASS_ALPHA key
// but rendered as a slider, not a hex field. The theme parser
// special-cases the key and reads it as a float.
@Widget(id = "customization.glass.alpha", displayName = "widget.customization.glass.alpha")
@Composable
fun CustomGlassAlphaWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    val value = settings.colorOverrides[ColorRole.GLASS_ALPHA]?.toFloatOrNull() ?: 0.6f
    val s = LocalStrings.current
    LabeledSlider(
        label  = s.customGlassAlpha,
        value  = value,
        range  = 0f..1f,
        format = "%.2f",
        onValueChange = { v ->
            ctx.update {
                copy(colorOverrides = colorOverrides + (ColorRole.GLASS_ALPHA to "%.3f".format(v)))
            }
        },
    )
}
