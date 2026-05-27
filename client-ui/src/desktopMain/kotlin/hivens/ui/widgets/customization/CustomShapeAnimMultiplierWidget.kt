package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.animMultiplier", displayName = "Скорость анимаций")
@Composable
fun CustomShapeAnimMultiplierWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    LabeledSlider(
        label  = "Animation speed",
        value  = settings.styleOverrides.animationMultiplier ?: 1f,
        range  = 0f..2f,
        format = "x%.2f",
        onValueChange = { v ->
            ctx.update { copy(styleOverrides = styleOverrides.copy(animationMultiplier = v)) }
        },
    )
}
