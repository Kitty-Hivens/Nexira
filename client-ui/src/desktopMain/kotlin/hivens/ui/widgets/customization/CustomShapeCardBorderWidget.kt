package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.cardBorder", displayName = "Толщина рамки карточек")
@Composable
fun CustomShapeCardBorderWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    val s = LocalStrings.current
    LabeledSlider(
        label  = s.customCardBorder,
        value  = settings.styleOverrides.cardBorderDp ?: 0f,
        range  = 0f..3f,
        format = "%.1fdp",
        onValueChange = { v ->
            ctx.update { copy(styleOverrides = styleOverrides.copy(cardBorderDp = v)) }
        },
    )
}
