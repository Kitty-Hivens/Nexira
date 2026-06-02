package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.accent.override", displayName = "widget.customization.accent.override")
@Composable
fun CustomAccentOverrideWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Column {
        SectionTitle(s.customizationAccentOverride)
        Spacer(Modifier.height(8.dp))
        HexField(
            initialHex   = settings.accentOverride ?: "",
            invalidLabel = s.customizationHexInvalid,
            onValidHex   = { hex -> ctx.update { copy(accentOverride = hex) } },
        )
        if (settings.accentOverride != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick  = { ctx.update { copy(accentOverride = null) } },
                shape    = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.customizationAccentClear) }
        }
    }
}
