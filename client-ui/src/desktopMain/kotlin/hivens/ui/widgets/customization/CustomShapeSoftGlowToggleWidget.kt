package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.components.NxSwitch
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.shape.softGlow", displayName = "widget.customization.shape.softGlow")
@Composable
fun CustomShapeSoftGlowToggleWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val settings by ctx.settings
    val s = LocalStrings.current

    Row(
        modifier              = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = s.customSoftGlow,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        NxSwitch(
            checked         = settings.styleOverrides.softGlowEnabled ?: true,
            onCheckedChange = { v ->
                ctx.update { copy(styleOverrides = styleOverrides.copy(softGlowEnabled = v)) }
            },
        )
    }
}
