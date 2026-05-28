package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.buttonCorner", displayName = "Скругление кнопок")
@Composable
fun CustomShapeButtonCornerWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    LabeledSlider(
        label  = "Button corner (dp)",
        value  = settings.styleOverrides.buttonCornerDp ?: 8f,
        range  = 0f..20f,
        format = "%.0fdp",
        onValueChange = { v ->
            ctx.update { copy(styleOverrides = styleOverrides.copy(buttonCornerDp = v)) }
        },
    )
}
