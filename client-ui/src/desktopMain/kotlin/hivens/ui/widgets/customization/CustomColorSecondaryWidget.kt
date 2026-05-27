package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.customization.ColorRole
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.color.secondary", displayName = "Цвет: secondary")
@Composable
fun CustomColorSecondaryWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    ColorRoleRow(
        role         = ColorRole.SECONDARY,
        currentHex   = settings.colorOverrides[ColorRole.SECONDARY],
        invalidLabel = s.customizationHexInvalid,
        onValidHex   = { hex -> ctx.update { copy(colorOverrides = colorOverrides + (ColorRole.SECONDARY to hex)) } },
        onClear      = { ctx.update { copy(colorOverrides = colorOverrides - ColorRole.SECONDARY) } },
    )
}
