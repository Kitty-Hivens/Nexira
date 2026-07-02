package hivens.ui.notifications.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationEvent
import hivens.ui.notifications.NotificationGroup
import hivens.ui.notifications.Severity
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun NotificationCard(
    group: NotificationGroup,
    now: Instant,
    onDismiss: () -> Unit,
) {
    // Include count in the key so dismiss-then-re-push under the same
    // sourceKey resets the expanded affordance; otherwise a freshly-
    // pushed Critical inherits the user's prior expanded=true and
    // appears already opened into stale history.
    var expanded by remember(group.sourceKey, group.count) { mutableStateOf(false) }
    val palette = NxTheme.colors
    val style = LocalStyle.current
    val accentColor = severityAccent(group.severity, group.kind, palette)
    // Critical pulses only when the active style allows motion; Brut stays static.
    val accentAlpha = if (group.severity == Severity.Critical && style.softGlowEnabled) criticalPulse() else 1f

    val scope = rememberCoroutineScope()
    val offsetX = remember(group.sourceKey) { Animatable(0f) }
    val cardShape = RoundedCornerShape(style.cardCorner)
    val density = LocalDensity.current
    // Fade the card as it is dragged toward the edge; the slide-off + the
    // stack's exit fade finish the gesture on release.
    val swipeFrac = (abs(offsetX.value) / with(density) { 380.dp.toPx() }).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .widthIn(min = 320.dp, max = 420.dp)
            .offset { IntOffset(offsetX.value.toInt(), 0) }
            .alpha(1f - 0.55f * swipeFrac)
            // Glass styles float on a soft shadow; flat (Brut) styles lean on a
            // hard border instead -- the shadow has no flat-style mapping.
            .then(if (style.softGlowEnabled) Modifier.shadow(8.dp, cardShape, clip = false) else Modifier)
            .clip(cardShape)
            // Toasts are transient alerts read against the live wallpaper -- even a
            // few percent of translucency tints them off-colour and reads as a glitch,
            // so they stay fully opaque regardless of the glass style.
            .background(palette.surface)
            .then(
                if (style.cardBorder > 0.dp) Modifier.border(style.cardBorder, palette.outline, cardShape)
                else Modifier,
            )
            // Swipe-to-dismiss: drag horizontally; past ~40% of the card width it
            // slides off and dismisses, otherwise it springs back. The close
            // button stays the keyboard / screen-reader path.
            .pointerInput(group.sourceKey) {
                val threshold = size.width * 0.4f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = offsetX.value
                        if (abs(dx) >= threshold) {
                            val target = if (dx > 0) size.width.toFloat() else -size.width.toFloat()
                            scope.launch {
                                offsetX.animateTo(target, tween(style.animationDurationMs(180)))
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(style.animationDurationMs(180))) }
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + delta) }
                    },
                )
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (accentColor != Color.Transparent) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderRow(
    group: NotificationGroup,
    now: Instant,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        NotificationAvatar(group.iconUrl, group.glyph)
        Spacer(Modifier.width(10.dp))
        Text(
            text       = group.sender,
            style      = MaterialTheme.typography.labelLarge,
            color      = NxTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.weight(1f),
        )
        // Hover-tooltip with absolute date; the inline label stays relative
        // ("Now" / "5s" / "1d") so the card reads quickly. A 1d label loses
        // the exact "yesterday at HH:mm" detail; the tooltip recovers it.
        TooltipArea(
            tooltip = {
                Surface(
                    color = NxTheme.colors.surface,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text     = strings.notificationAbsoluteTime(group.latest.createdAt),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = NxTheme.colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            },
            delayMillis = 400,
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 12.dp)),
        ) {
            Text(
                text  = relativeTime(group.latest.createdAt, now, strings),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
            )
        }
        // Chevron is conditional on count; close is unconditional. Sticky
        // kinds (Sticky, ActionRequired) never auto-dismiss, so grouped
        // cards without a close leave the user trapped -- the action
        // buttons all have side effects, none of them just "close this".
        if (group.count > 1) {
            Spacer(Modifier.width(6.dp))
            // IconButton wraps the row so screen readers / keyboards see a
            // single focusable target with a stable contentDescription; the
            // expanded label flips so screen-reader output reflects state.
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = group.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NxTheme.colors.textSecondary,
                    )
                    Symbol(icon = if (expanded) NxIcon.ExpandLess else NxIcon.ExpandMore,
                        contentDescription = if (expanded) strings.notificationCollapseHistory
                                             else strings.notificationExpandHistory,
                        modifier          = Modifier.size(16.dp),
                        tint              = NxTheme.colors.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Symbol(icon = NxIcon.Close,
                contentDescription = strings.notificationDismiss,
                modifier          = Modifier.size(14.dp),
                tint              = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun EventBody(event: NotificationEvent, accentColor: Color) {
    Text(
        text       = event.title,
        style      = MaterialTheme.typography.bodyMedium,
        color      = NxTheme.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
    )
    val body = event.body
    if (!body.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text  = body,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
    }
    val progress = event.progress
    if (progress != null) {
        Spacer(Modifier.height(8.dp))
        // Track is surfaceVariant, not surface -- against the card's own surface
        // fill the old track was invisible, so the bar read as a bare sliver.
        if (progress.isNaN()) {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color      = accentColor,
                trackColor = NxTheme.colors.surfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                progress   = { progress.coerceIn(0f, 1f) },
                modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color      = accentColor,
                trackColor = NxTheme.colors.surfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsRow(actions: List<NotifAction>, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action ->
            TextButton(
                onClick = {
                    action.onClick()
                    onDismiss()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text  = action.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = NxTheme.colors.primary,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(event: NotificationEvent, now: Instant) {
    val strings = LocalStrings.current
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = event.title,
            style    = MaterialTheme.typography.labelMedium,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = relativeTime(event.createdAt, now, strings),
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.textSecondary.copy(alpha = 0.55f),
        )
    }
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

// Routes (Severity, Kind) onto the active palette. Severity drives the
// color band; Kind.Progress promotes Info to the progress accent so the
// card visibly tracks in-flight work. Info+non-Progress has no stripe --
// caller elides the side-bar -- so Color.Transparent is the safe sentinel.
private fun severityAccent(severity: Severity, kind: Kind, colors: NxColors): Color = when (severity) {
    Severity.Info     -> if (kind == Kind.Progress) colors.progressAccent else Color.Transparent
    Severity.Success  -> colors.success
    Severity.Warn     -> colors.warnAccent
    Severity.Critical -> colors.criticalAccent
}

private fun relativeTime(created: Instant, now: Instant, strings: AppStrings): String {
    val seconds = Duration.between(created, now).seconds
    return when {
        seconds < 5      -> strings.notifTimeNow
        seconds < 60     -> strings.notifTimeSeconds(seconds)
        seconds < 3600   -> strings.notifTimeMinutes(seconds / 60)
        seconds < 86_400 -> strings.notifTimeHours(seconds / 3600)
        else             -> strings.notifTimeDays(seconds / 86_400)
    }
}
