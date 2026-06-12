package hivens.ui.widgets.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.notifications.PersistedNotification
import hivens.ui.notifications.Severity
import hivens.ui.notifications.render.NotificationAvatar
import hivens.ui.theme.CelestiaColors
import hivens.ui.theme.CelestiaTheme
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberAction
import hivens.widget.api.rememberSource
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import java.time.Instant

/**
 * Placeable message-history widget: the durable counterpart to the live
 * top-right toast stack. Reads the persisted [NotificationArchiveStore] log
 * (survives auto-dismiss and restart) and renders it newest-first, reusing the
 * shared [NotificationAvatar]. The user drops it into any slot via the editor.
 */
@Widget(id = "notifications.history", displayName = "widget.notifications.history")
@Composable
fun NotificationHistoryWidget(instance: WidgetInstance) {
    // Bound declaratively to the notifications source for the read and the clear
    // command for the write -- the widget drives no service directly.
    val log by rememberSource(Sources.Notifications)
    val clearLog = rememberAction(Commands.ClearNotifications)
    val strings = LocalStrings.current
    val palette = CelestiaTheme.colors

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text       = strings.widgetLabel("widget.notifications.history"),
                style      = MaterialTheme.typography.labelLarge,
                color      = palette.textSecondary,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.weight(1f),
            )
            if (log.isNotEmpty()) {
                TextButton(onClick = clearLog) {
                    Text(
                        text  = strings.notifHistoryClear,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (log.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text  = strings.notifHistoryEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary.copy(alpha = 0.7f),
                )
            }
        } else {
            Column(
                modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                log.forEach { entry -> HistoryRow(entry) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: PersistedNotification) {
    val strings = LocalStrings.current
    val palette = CelestiaTheme.colors
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationAvatar(entry.iconUrl, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = entry.title,
                style      = MaterialTheme.typography.bodyMedium,
                color      = severityColor(entry.severity, palette),
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            val body = entry.body
            if (!body.isNullOrBlank()) {
                Text(
                    text     = body,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text  = strings.notificationAbsoluteTime(Instant.ofEpochSecond(entry.createdAtEpoch)),
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary.copy(alpha = 0.6f),
        )
    }
}

// Critical / Warn tint the title so failures stand out when scanning the log;
// Info / Success read as normal primary text.
private fun severityColor(severity: Severity, colors: CelestiaColors): Color = when (severity) {
    Severity.Critical -> colors.criticalAccent
    Severity.Warn     -> colors.warnAccent
    else              -> colors.textPrimary
}
