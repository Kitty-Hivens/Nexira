package hivens.ui.widgets.bgsettings

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
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.reset", displayName = "widget.bg.reset")
@Composable
fun BgResetWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current

    OutlinedButton(
        onClick        = { ctx.update { BackgroundSettings() } },
        shape          = RoundedCornerShape(8.dp),
        modifier       = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.backgroundReset)
    }
}
