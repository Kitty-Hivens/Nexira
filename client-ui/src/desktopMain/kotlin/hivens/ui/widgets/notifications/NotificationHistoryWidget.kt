package hivens.ui.widgets.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.PersistedNotification
import hivens.ui.notifications.Severity
import hivens.ui.notifications.render.NotificationAvatar
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberAction
import hivens.widget.api.rememberCommand
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberSource
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class NotificationHistoryProps(
    // false: header on top, list opens downward. true: header at the bottom,
    // list opens upward (anchor the panel to the bottom of its footprint).
    @PropLabel("widget.notifications.history.expandUp") val expandUp: Boolean = false,
    // 12-hour clock with an am/pm marker instead of 24-hour.
    @PropLabel("widget.notifications.history.clock12h") val clock12h: Boolean = false,
    // Stack the timestamp vertically (hh / mm / ss|am) for narrow placements.
    @PropLabel("widget.notifications.history.verticalTime") val verticalTime: Boolean = false,
)

// Footprint of a header pill button (icon 16 + 6dp padding each side); reused as
// the placeholder width that keeps the chevron pinned left when the trailing
// action is absent.
private val PILL_BUTTON_SIZE = 28.dp

/**
 * Placeable message-history widget: the durable counterpart to the live
 * top-right toast stack. Reads the persisted [NotificationArchiveStore] log
 * (survives auto-dismiss and restart).
 *
 * Self-contained outlined panel: collapsed it is a compact bar -- an expand
 * chevron pill and a "<N> messages" pill. Expanding animates the panel open with
 * the messages inside it; the clear (trash) pill appears only while expanded.
 * A single message is swiped to the right to dismiss it; the trash pill slides
 * the whole list out before wiping it. Consecutive identical entries fold into
 * one row with a count, mirroring the live stack's progress coalescing.
 */
@Widget(
    id = "notifications.history",
    displayName = "widget.notifications.history",
    propsClass = NotificationHistoryProps::class,
    surface = """{"fill":"base","opacity":0.5,"border":{"widthDp":1.0,"color":"outline"}}""",
)
@Composable
fun NotificationHistoryWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<NotificationHistoryProps>()
    // Bound declaratively to the notifications source for the read and the clear
    // command for the write; the store is injected for per-message removal.
    val log by rememberSource(Sources.Notifications)
    val clearLog = rememberAction(Commands.ClearNotifications)
    // "Do not disturb" lives in the NotificationCenter; the widget reflects it on
    // the mute toggle and flips it through the command -- the live stack reads the
    // same flow to gate popups.
    val doNotDisturb by rememberSource(Sources.DoNotDisturb)
    val setDoNotDisturb = rememberCommand(Commands.SetDoNotDisturb)
    val store: NotificationArchiveStore = koinInject()
    val palette = NxTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val groups = remember(log) { groupHistory(log) }
    val outline = palette.outline.copy(alpha = 0.4f)
    val scope = rememberCoroutineScope()

    // Clear slides the whole list out to the right, wipes it, then collapses the
    // panel -- once there is nothing to show, the expanded drawer is just an empty
    // box, so it folds shut on its own.
    val clearOffset = remember { Animatable(0f) }
    var panelWidthPx by remember { mutableStateOf(1f) }
    val animatedClear: () -> Unit = {
        scope.launch {
            clearOffset.animateTo(panelWidthPx)
            clearLog()
            expanded = false
            clearOffset.snapTo(0f)
        }
    }
    // Dismissing the last remaining group empties the log; collapse with it so the
    // user is not left staring at an open, empty drawer.
    val onDismissGroup: (HistoryGroup) -> Unit = { group ->
        if (groups.size <= 1) expanded = false
        store.remove { it in group.members }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { panelWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .padding(8.dp),
    ) {
        if (props.expandUp) {
            NotificationDrawer(expanded, groups, log.isEmpty(), clearOffset.value, onDismissGroup, props.clock12h, props.verticalTime, fromTop = false)
            HistoryHeader(expanded, true, log.size, expanded && log.isNotEmpty(), doNotDisturb, outline, { expanded = !expanded }, animatedClear) { setDoNotDisturb(!doNotDisturb) }
        } else {
            HistoryHeader(expanded, false, log.size, expanded && log.isNotEmpty(), doNotDisturb, outline, { expanded = !expanded }, animatedClear) { setDoNotDisturb(!doNotDisturb) }
            NotificationDrawer(expanded, groups, log.isEmpty(), clearOffset.value, onDismissGroup, props.clock12h, props.verticalTime, fromTop = true)
        }
    }
}

@Composable
private fun HistoryHeader(
    expanded: Boolean,
    expandUp: Boolean,
    messageCount: Int,
    showTrash: Boolean,
    dndActive: Boolean,
    outline: Color,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onToggleDnd: () -> Unit,
) {
    val strings = LocalStrings.current
    // The chevron points toward where the list will go: down for a downward
    // drawer (and up to close it), inverted for an upward one.
    val pointsUp = if (expandUp) !expanded else expanded
    // Count pill centers over the edge row; the chevron pins to the start and the
    // contextual action to the end -- clear while expanded, "do not disturb" while
    // collapsed. The two trailing actions never coexist, so the count stays put.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PillButton(
                icon               = if (pointsUp) NxIcon.KeyboardArrowUp else NxIcon.KeyboardArrowDown,
                contentDescription = if (expanded) strings.notificationCollapseHistory else strings.notificationExpandHistory,
                outline            = outline,
                onClick            = onToggle,
            )
            if (expanded) {
                if (showTrash) {
                    PillButton(
                        icon               = NxIcon.Delete,
                        contentDescription = strings.notifHistoryClear,
                        outline            = outline,
                        onClick            = onClear,
                    )
                } else {
                    // Keep the chevron pinned left when there is no trailing action.
                    Spacer(Modifier.size(PILL_BUTTON_SIZE))
                }
            } else {
                PillButton(
                    icon               = NxIcon.NotificationsOff,
                    contentDescription = strings.notifDoNotDisturb,
                    outline            = outline,
                    active             = dndActive,
                    onClick            = onToggleDnd,
                )
            }
        }
        CountPill(
            text     = strings.notifCountTitle(messageCount),
            outline  = outline,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun PillButton(
    icon: IconKey,
    contentDescription: String,
    outline: Color,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = NxTheme.colors
    // Active = the toggle is engaged (mute on): tint + fill shift to the accent so
    // the state reads at a glance without a separate label.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.primary.copy(alpha = 0.18f) else palette.surface.copy(alpha = 0.45f))
            .border(1.dp, if (active) palette.primary.copy(alpha = 0.6f) else outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon = icon,
            contentDescription = contentDescription,
            tint               = if (active) palette.primary else palette.textSecondary,
            modifier           = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CountPill(text: String, outline: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(NxTheme.colors.surface.copy(alpha = 0.35f))
            .border(1.dp, outline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelMedium,
            color      = NxTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
        )
    }
}

@Composable
private fun NotificationDrawer(
    expanded: Boolean,
    groups: List<HistoryGroup>,
    isEmpty: Boolean,
    clearOffsetPx: Float,
    onDismissGroup: (HistoryGroup) -> Unit,
    ampm: Boolean,
    verticalTime: Boolean,
    fromTop: Boolean,
) {
    val strings = LocalStrings.current
    val palette = NxTheme.colors
    val edge = if (fromTop) Alignment.Top else Alignment.Bottom
    AnimatedVisibility(
        visible = expanded,
        enter   = expandVertically(expandFrom = edge) + fadeIn(),
        exit    = shrinkVertically(shrinkTowards = edge) + fadeOut(),
    ) {
        if (isEmpty) {
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
            // wraps its content). The clear animation slides the whole list right.
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .offset { IntOffset(clearOffsetPx.roundToInt(), 0) }
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { group ->
                    key(group.head.sourceKey, group.head.createdAtEpoch, group.head.title) {
                        SwipeableHistoryRow(
                            entry        = group.head,
                            count        = group.count,
                            ampm         = ampm,
                            verticalTime = verticalTime,
                            onDismiss    = { onDismissGroup(group) },
                        )
                    }
                }
            }
        }
    }
}

// Swipe a row to the right to dismiss it: the offset tracks the drag, snaps back
// if released early, or slides off and removes the group once past the threshold.
@Composable
private fun SwipeableHistoryRow(
    entry: PersistedNotification,
    count: Int,
    ampm: Boolean,
    verticalTime: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(1f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        // Dismiss is a rightward swipe; clamp the left side at rest.
                        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        if (offsetX.value > widthPx * 0.4f) {
                            scope.launch { offsetX.animateTo(widthPx); onDismiss() }
                        } else {
                            scope.launch { offsetX.animateTo(0f) }
                        }
                    },
                )
            }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer { alpha = (1f - offsetX.value / widthPx).coerceIn(0f, 1f) },
    ) {
        HistoryRow(entry, count, ampm, verticalTime)
    }
}

private data class HistoryGroup(val head: PersistedNotification, val members: List<PersistedNotification>) {
    val count: Int get() = members.size
}

// Collapse runs of consecutive identical entries (same source + title +
// severity). The log is newest-first, so the kept head is the most recent of
// each run; only adjacent duplicates fold, so a re-occurrence after other
// activity stays a separate row. Each group keeps its members so a swipe removes
// exactly that run.
private fun groupHistory(log: List<PersistedNotification>): List<HistoryGroup> {
    val out = ArrayList<HistoryGroup>(log.size)
    val cur = ArrayList<PersistedNotification>()
    fun flush() {
        if (cur.isNotEmpty()) {
            out.add(HistoryGroup(cur.first(), cur.toList()))
            cur.clear()
        }
    }
    for (entry in log) {
        val head = cur.firstOrNull()
        if (head != null &&
            head.sourceKey == entry.sourceKey &&
            head.title == entry.title &&
            head.severity == entry.severity
        ) {
            cur.add(entry)
        } else {
            flush()
            cur.add(entry)
        }
    }
    flush()
    return out
}

@Composable
private fun HistoryRow(entry: PersistedNotification, count: Int, ampm: Boolean, verticalTime: Boolean) {
    val strings = LocalStrings.current
    val palette = NxTheme.colors
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationAvatar(entry.iconUrl, entry.glyph, size = 26.dp)
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
        TimeStamp(
            epoch    = entry.createdAtEpoch,
            ampm     = ampm,
            vertical = verticalTime,
            color    = palette.textSecondary.copy(alpha = 0.6f),
        )
    }
}

// Time-only stamp (the full date is noise in the history). Horizontal HH:MM:SS,
// or the parts stacked for narrow placements; 12-hour adds an am/pm marker.
@Composable
private fun TimeStamp(epoch: Long, ampm: Boolean, vertical: Boolean, color: Color) {
    val ldt = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault())
    val hour = if (ampm) ((ldt.hour + 11) % 12) + 1 else ldt.hour
    val hh = "%02d".format(hour)
    val mm = "%02d".format(ldt.minute)
    val ss = "%02d".format(ldt.second)
    val meridiem = if (ldt.hour < 12) "am" else "pm"
    val style = MaterialTheme.typography.labelSmall
    if (vertical) {
        Column(horizontalAlignment = Alignment.End) {
            Text(hh, style = style, color = color)
            Text(mm, style = style, color = color)
            Text(if (ampm) meridiem else ss, style = style, color = color)
        }
    } else {
        Text(
            text     = if (ampm) "$hh:$mm:$ss $meridiem" else "$hh:$mm:$ss",
            style    = style,
            color    = color,
            maxLines = 1,
        )
    }
}

// Critical / Warn tint the title so failures stand out when scanning the log;
// Info / Success read as normal primary text.
private fun severityColor(severity: Severity, colors: NxColors): Color = when (severity) {
    Severity.Critical -> colors.criticalAccent
    Severity.Warn     -> colors.warnAccent
    else              -> colors.textPrimary
}
