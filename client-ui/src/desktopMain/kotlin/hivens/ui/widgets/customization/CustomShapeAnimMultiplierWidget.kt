package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.animMultiplier", displayName = "widget.customization.shape.animMultiplier")
@Composable
fun CustomShapeAnimMultiplierWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    val s = LocalStrings.current
    LabeledSlider(
        label  = s.customAnimSpeed,
        value  = settings.styleOverrides.animationMultiplier ?: 1f,
        range  = 0f..2f,
        format = "x%.2f",
        onValueChange = { v ->
            ctx.update { copy(styleOverrides = styleOverrides.copy(animationMultiplier = v)) }
        },
    )
}
