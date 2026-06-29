package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hivens.ui.nx.NxSwitch
import hivens.ui.theme.NxTheme

/**
 * Glass-tinted panel background for the few legacy Settings rows still off an
 * [hivens.ui.nx.NxSection] plane (the editor's surface-properties panel). The Settings
 * page stays neutral; Flat treatment applies only to content surfaces (GlassCard,
 * library cards) elsewhere.
 */
@Composable
internal fun settingsRowBackground(): Color =
    NxTheme.colors.background.copy(alpha = 0.4f)

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = NxTheme.colors.textPrimary)
        NxSwitch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
