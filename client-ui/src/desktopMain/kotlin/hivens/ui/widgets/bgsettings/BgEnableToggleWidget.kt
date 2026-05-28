package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.enable.toggle", displayName = "Фон вкл/выкл")
@Composable
fun BgEnableToggleWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wallpaper, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(s.backgroundEnable, fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.textPrimary)
        }
        Switch(
            checked         = settings.enabled,
            onCheckedChange = { ctx.update { copy(enabled = it) } },
            colors          = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
            ),
        )
    }
}
