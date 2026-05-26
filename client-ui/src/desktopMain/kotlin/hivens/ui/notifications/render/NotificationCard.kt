package hivens.ui.notifications.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.notifications.NotificationEvent
import hivens.ui.notifications.NotificationGroup
import hivens.ui.notifications.Severity
import hivens.ui.theme.CelestiaTheme
import java.time.Duration
import java.time.Instant

/**
 * One stacked notification group. Top row: avatar + sender + relative
 * time + (counter+chevron when history exists). Middle: title + body
 * of the latest event, optional progress bar inline with the accent.
 * Bottom: action chips, if any.
 *
 * Expanded view (chevron click) reveals previous events of the group
 * as tighter rows under the latest one. Severity above Info adds a
 * slim accent bar on the left; Critical pulses.
 */
@Composable
fun NotificationCard(
    group: NotificationGroup,
    now: Instant,
    onDismiss: () -> Unit,
) {
    var expanded by remember(group.sourceKey) { mutableStateOf(false) }
    val accentColor = severityAccent(group.severity)
    val accentAlpha = if (group.severity == Severity.Critical) criticalPulse() else 1f

    Box(
        modifier = Modifier
            .widthIn(min = 320.dp, max = 420.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(CelestiaTheme.colors.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Accent strip on the left. Hidden for Info to keep
            // routine notifications visually quiet; promoted to a
            // narrow vertical band for Warn / Progress / Success and
            // a wider pulsing band for Critical.
            if (group.severity != Severity.Info) {
                Box(
                    modifier = Modifier
                        .width(if (group.severity == Severity.Critical) 4.dp else 3.dp)
                        .fillMaxHeight()
                        .background(accentColor.copy(alpha = accentAlpha))
                )
            }

            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                HeaderRow(
                    group       = group,
                    now         = now,
                    expanded    = expanded,
                    onToggle    = { if (group.count > 1) expanded = !expanded },
                    onDismiss   = onDismiss,
                )

                Spacer(Modifier.height(6.dp))

                EventBody(event = group.latest, accentColor = accentColor)

                if (group.latest.actions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ActionsRow(actions = group.latest.actions, onDismiss = onDismiss)
                }

                // History rows: collapsed by default; expanded shows
                // every prior event (newest-first already; we skip
                // index 0 which is the `latest` already rendered).
                AnimatedVisibility(
                    visible = expanded,
                    enter   = expandVertically(),
                    exit    = shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        group.events.drop(1).forEach { event ->
                            HistoryRow(event = event, now = now)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    group: NotificationGroup,
    now: Instant,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AvatarSlot(group)
        Spacer(Modifier.width(10.dp))
        Text(
            text       = group.sender,
            style      = MaterialTheme.typography.labelLarge,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.weight(1f),
        )
        Text(
            text  = relativeTime(group.latest.createdAt, now),
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
        )
        if (group.count > 1) {
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier.clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = group.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
                Icon(
                    imageVector       = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier          = Modifier.size(16.dp),
                    tint              = CelestiaTheme.colors.textSecondary,
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
            Text(
                text     = "✕",
                modifier = Modifier.clickable(onClick = onDismiss).padding(2.dp),
                style    = MaterialTheme.typography.labelSmall,
                color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun EventBody(event: NotificationEvent, accentColor: Color) {
    Text(
        text       = event.title,
        style      = MaterialTheme.typography.bodyMedium,
        color      = CelestiaTheme.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
    )
    val body = event.body
    if (!body.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text  = body,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
    val progress = event.progress
    if (progress != null) {
        Spacer(Modifier.height(8.dp))
        if (progress.isNaN()) {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                color      = accentColor,
                trackColor = CelestiaTheme.colors.surface,
            )
        } else {
            LinearProgressIndicator(
                progress   = { progress.coerceIn(0f, 1f) },
                modifier   = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                color      = accentColor,
                trackColor = CelestiaTheme.colors.surface,
            )
        }
    }
}

@Composable
private fun ActionsRow(actions: List<hivens.ui.notifications.NotifAction>, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action ->
            TextButton(
                onClick = {
                    action.onClick()
                    // Action click implicitly dismisses unless the
                    // action is a "show more" type. For simplicity in
                    // v0 every action dismisses; explicit "keep open"
                    // semantics can land later when a real driver
                    // needs them.
                    onDismiss()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text  = action.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = CelestiaTheme.colors.primary,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(event: NotificationEvent, now: Instant) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = event.title,
            style    = MaterialTheme.typography.labelMedium,
            color    = CelestiaTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = relativeTime(event.createdAt, now),
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AvatarSlot(group: hivens.ui.notifications.NotificationGroup) {
    // v0: solid neutral square placeholder. Wired to Coil + real
    // pack icon URL when [[project_pack_rich_metadata]] propagates
    // summary.icon_url into PackInstance.cachedManifest (or a
    // sibling field).
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CelestiaTheme.colors.textSecondary.copy(alpha = 0.18f))
    )
}

@Composable
private fun criticalPulse(): Float {
    val transition = rememberInfiniteTransition(label = "critical-pulse")
    val v by transition.animateFloat(
        initialValue = 0.55f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "critical-pulse-alpha",
    )
    return v
}

private fun severityAccent(severity: Severity): Color = when (severity) {
    Severity.Info     -> Color.Transparent
    Severity.Progress -> Color(0xFF6A84FF)
    Severity.Success  -> Color(0xFF4FC76E)
    Severity.Warn     -> Color(0xFFE0B341)
    Severity.Critical -> Color(0xFFD8484A)
}

private fun relativeTime(created: Instant, now: Instant): String {
    val seconds = Duration.between(created, now).seconds
    return when {
        seconds < 5         -> "Now"
        seconds < 60        -> "${seconds}s"
        seconds < 3600      -> "${seconds / 60}m"
        seconds < 86_400    -> "${seconds / 3600}h"
        else                -> "${seconds / 86_400}d"
    }
}
