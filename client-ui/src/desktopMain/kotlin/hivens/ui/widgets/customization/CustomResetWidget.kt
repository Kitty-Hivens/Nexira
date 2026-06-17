package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "customization.reset", displayName = "widget.customization.reset")
@Composable
fun CustomResetWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    var confirming by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick        = { confirming = true },
        shape          = MaterialTheme.shapes.small,
        modifier       = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Symbol(NxIcon.RestartAlt, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.customizationReset)
    }

    if (confirming) {
        DestructiveConfirmDialog(
            title        = s.customizationResetConfirmTitle,
            body         = s.customizationResetConfirmBody,
            confirmLabel = s.customizationReset,
            onConfirm    = { ctx.update { CustomizationSettings() } },
            onDismiss    = { confirming = false },
        )
    }
}
