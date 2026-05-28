package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.cardCorner", displayName = "Скругление карточек")
@Composable
fun CustomShapeCardCornerWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    LabeledSlider(
        label  = "Card corner (dp)",
        value  = settings.styleOverrides.cardCornerDp ?: 12f,
        range  = 0f..24f,
        format = "%.0fdp",
        onValueChange = { v ->
            ctx.update { copy(styleOverrides = styleOverrides.copy(cardCornerDp = v)) }
        },
    )
}
