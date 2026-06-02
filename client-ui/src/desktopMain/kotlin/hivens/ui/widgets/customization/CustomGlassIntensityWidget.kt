package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.glass.intensity", displayName = "widget.customization.glass.intensity")
@Composable
fun CustomGlassIntensityWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.customizationGlassIntensity, settings.glassIntensity, 0f..1f, "%.0f%%", 100f) {
        ctx.update { copy(glassIntensity = it) }
    }
}
