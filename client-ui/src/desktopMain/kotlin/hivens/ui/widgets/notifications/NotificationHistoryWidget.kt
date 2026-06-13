package hivens.ui.widgets.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * top-right toast stack. Reads the persisted [hivens.ui.notifications.NotificationArchiveStore]
 * log (survives auto-dismiss and restart). Starts collapsed -- a bottom-pinned
 * header bar -- and a chevron opens the list upward over the widget footprint.
 * Consecutive identical entries collapse into one row with a count, mirroring
 * how the live stack coalesces a progress run.
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
    var expanded by remember { mutableStateOf(false) }
    val groups = remember(log) { groupHistory(log) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Header at the top; the chevron opens the drawer downward.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector        = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) strings.notificationCollapseHistory else strings.notificationExpandHistory,
                    tint               = palette.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text       = strings.widgetLabel("widget.notifications.history"),
                style      = MaterialTheme.typography.labelLarge,
                color      = palette.textSecondary,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.weight(1f),
            )
            if (log.isNotEmpty()) {
                Text(
                    text  = "${log.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = clearLog, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector        = Icons.Default.Delete,
                        contentDescription = strings.notifHistoryClear,
                        tint               = palette.textSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Drawer body grows downward below the header.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Extracted so AnimatedVisibility resolves to its non-scoped overload
            // rather than the ColumnScope one the enclosing Column would shadow in.
            NotificationDrawer(expanded = expanded, log = log, groups = groups)
        }
    }
}

@Composable
private fun NotificationDrawer(
    expanded: Boolean,
    log: List<PersistedNotification>,
    groups: List<HistoryGroup>,
) {
    val strings = LocalStrings.current
    val palette = CelestiaTheme.colors
    AnimatedVisibility(
        visible = expanded,
        enter   = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit    = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        if (log.isEmpty()) {
            Box(
                modifier         = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = strings.notifHistoryEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary.copy(alpha = 0.7f),
                )
            }
        } else {
            Column(
                modifier            = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { group -> HistoryRow(group.head, group.count) }
            }
        }
    }
}

private data class HistoryGroup(val head: PersistedNotification, val count: Int)

// Collapse runs of consecutive identical entries (same source + title +
// severity). The log is newest-first, so the kept head is the most recent of
// each run; only adjacent duplicates fold, so a re-occurrence after other
// activity stays a separate row.
private fun groupHistory(log: List<PersistedNotification>): List<HistoryGroup> {
    val out = ArrayList<HistoryGroup>(log.size)
    for (entry in log) {
        val last = out.lastOrNull()
        if (last != null &&
            last.head.sourceKey == entry.sourceKey &&
            last.head.title == entry.title &&
            last.head.severity == entry.severity
        ) {
            out[out.lastIndex] = last.copy(count = last.count + 1)
        } else {
            out.add(HistoryGroup(entry, 1))
        }
    }
    return out
}

@Composable
private fun HistoryRow(entry: PersistedNotification, count: Int) {
    val strings = LocalStrings.current
    val palette = CelestiaTheme.colors
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationAvatar(entry.iconUrl, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = entry.title,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = severityColor(entry.severity, palette),
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f, fill = false),
                )
                if (count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text       = strings.notifGroupCount(count),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = palette.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
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
