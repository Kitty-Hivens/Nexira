package hivens.ui.widgets.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.customization.ColorRole
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.color.criticalAccent", displayName = "Цвет: criticalAccent")
@Composable
fun CustomColorCriticalAccentWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    ColorRoleRow(
        role         = ColorRole.CRITICAL_ACCENT,
        currentHex   = settings.colorOverrides[ColorRole.CRITICAL_ACCENT],
        invalidLabel = s.customizationHexInvalid,
        onValidHex   = { hex -> ctx.update { copy(colorOverrides = colorOverrides + (ColorRole.CRITICAL_ACCENT to hex)) } },
        onClear      = { ctx.update { copy(colorOverrides = colorOverrides - ColorRole.CRITICAL_ACCENT) } },
    )
}
