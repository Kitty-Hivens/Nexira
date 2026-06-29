package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.nx.NxSwitch
import hivens.ui.theme.NxTheme

/**
 * Glass-tinted panel background for legacy Settings rows that have not yet moved
 * onto an [hivens.ui.nx.NxSection] plane. Opted out of [hivens.ui.theme.LocalStyle.cardSurface]
 * -- the Settings page stays neutral; Flat treatment applies only to content surfaces
 * (GlassCard, library cards) elsewhere.
 */
@Composable
internal fun settingsRowBackground(): Color =
    NxTheme.colors.background.copy(alpha = 0.4f)

/**
 * Section header for the Settings sections still off the nx-ui primitives (currently
 * Diagnostics). Small bold caps title + thin underline. Migrated sections use the
 * [hivens.ui.nx.NxSection] header instead.
 */
@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style      = MaterialTheme.typography.bodySmall,
        color      = NxTheme.colors.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = NxTheme.colors.primary.copy(alpha = 0.3f))
    Spacer(Modifier.height(8.dp))
}

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
