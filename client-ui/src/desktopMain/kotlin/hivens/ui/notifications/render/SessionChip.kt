package hivens.ui.notifications.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.SessionRegistry.ActiveSession
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import java.time.Duration

@Composable
fun ActiveSessionsSection(registry: SessionRegistry = koinInject()) { // TODO: Function "ActiveSessionsSection" is never used
    val active by registry.active.collectAsState()
    if (active.isEmpty()) return
    val s = LocalStrings.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text       = s.sessionsActiveTitle,
            style      = MaterialTheme.typography.labelSmall,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        active.values.forEach { session ->
            SessionChip(session = session)
        }
    }
}

@Composable
fun SessionChip(session: ActiveSession) {
    val uptime by session.uptime.collectAsState()
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(CelestiaTheme.colors.surface)
            .clickable { session.showConsole() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF4FC76E))
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = session.packDisplayName,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text  = formatUptime(uptime),
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            IconButton(onClick = session.showConsole) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Default.MenuOpen,
                    contentDescription = s.notifActionShowConsole,
                    tint              = CelestiaTheme.colors.textSecondary,
                )
            }
            IconButton(onClick = session.abort) {
                Icon(
                    imageVector       = Icons.Default.Stop,
                    contentDescription = s.notifActionStop,
                    tint              = CelestiaTheme.colors.error,
                )
            }
        }
    }
}

private fun formatUptime(d: Duration): String {
    val totalSeconds = d.seconds
    if (totalSeconds < 60)   return "${totalSeconds}s"
    if (totalSeconds < 3600) return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
