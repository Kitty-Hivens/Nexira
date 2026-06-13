package hivens.ui.widgets.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
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
 * top-right toast stack. Reads the persisted
 * [hivens.ui.notifications.NotificationArchiveStore] log (survives auto-dismiss
 * and restart).
 *
 * Self-contained outlined panel: collapsed it is just a small bar -- an
 * expand chevron pill and a "<N> messages" pill. Expanding animates the panel
 * open and the messages live inside it; the clear (trash) pill appears only
 * while expanded. Consecutive identical entries fold into one row with a count,
 * mirroring how the live stack coalesces a progress run.
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
    val outline = palette.outline.copy(alpha = 0.4f)

    // The whole widget is one outlined, rounded panel that wraps its content --
    // a compact bar when collapsed, growing as the drawer opens.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassSurfaceAlpha(0.5f))
            .border(1.dp, outline, RoundedCornerShape(16.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            PillButton(
                icon               = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) strings.notificationCollapseHistory else strings.notificationExpandHistory,
                outline            = outline,
                onClick            = { expanded = !expanded },
            )
            Spacer(Modifier.width(6.dp))
            CountPill(text = strings.notifCountTitle(log.size), outline = outline)
            Spacer(Modifier.weight(1f))
            // Trash only once expanded (and only when there is something to clear).
            if (expanded && log.isNotEmpty()) {
                PillButton(
                    icon               = Icons.Default.Delete,
                    contentDescription = strings.notifHistoryClear,
                    outline            = outline,
                    onClick            = clearLog,
                )
            }
        }

        // Messages live inside the panel; the drawer opens downward with animation.
        NotificationDrawer(expanded = expanded, log = log, groups = groups)
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    contentDescription: String,
    outline: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(glassSurfaceAlpha(0.45f))
            .border(1.dp, outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = CelestiaTheme.colors.textSecondary,
            modifier           = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CountPill(text: String, outline: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(glassSurfaceAlpha(0.35f))
            .border(1.dp, outline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelMedium,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
        )
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
                modifier         = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = strings.notifHistoryEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary.copy(alpha = 0.7f),
                )
            }
        } else {
            // Bounded so the inner scroll has a height to work with (the panel
            // wraps its content, so without a cap the list would grow unbounded).
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
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
