package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.density", displayName = "Плотность UI")
@Composable
fun CustomDensityWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.customizationDensity, settings.densityScale, 0.85f..1.15f, "%.2fx") {
        ctx.update { copy(densityScale = it) }
    }
}
