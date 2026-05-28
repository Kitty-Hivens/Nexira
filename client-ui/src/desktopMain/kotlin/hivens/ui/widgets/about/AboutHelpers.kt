package hivens.ui.widgets.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.platform.SystemActions
import hivens.ui.theme.CelestiaTheme

// Shared composables reused across the about widgets. SectionLabel
// titles a group, InfoRow renders one label/value pair (5 system
// rows use this), LinkButton is the outlined external-link affordance.

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text          = text,
        style         = MaterialTheme.typography.labelSmall,
        fontWeight    = FontWeight.Bold,
        color         = CelestiaTheme.colors.primary,
        letterSpacing = 1.sp,
    )
}

@Composable
internal fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = CelestiaTheme.colors.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text       = value,
            color      = CelestiaTheme.colors.textPrimary,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign  = TextAlign.End,
            modifier   = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun LinkButton(label: String, url: String, icon: ImageVector) {
    OutlinedButton(
        onClick  = { SystemActions.openUrl(url) },
        modifier = Modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.small,
        border   = BorderStroke(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.2f)),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = CelestiaTheme.colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(label, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text("↗", color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f))
    }
}
