package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.reset", displayName = "Сброс кастомизации")
@Composable
fun CustomResetWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current

    OutlinedButton(
        onClick        = { ctx.update { CustomizationSettings() } },
        shape          = RoundedCornerShape(8.dp),
        modifier       = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.customizationReset)
    }
}
