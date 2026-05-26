package hivens.ui.notifications.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.notifications.NotificationCenter
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.time.Instant

/**
 * Top-level host for the notification stack. Place once inside
 * AppShell so the cards render above every screen. Visually anchors
 * to the top-right with a small inset; future position prefs would
 * pick a different alignment without touching consumer code.
 *
 * Stack discipline:
 *  - Up to [MAX_VISIBLE] cards rendered at once
 *  - Overflow rolls into a "+N more" footer that opens an inline
 *    drawer to show the rest (deferred to v1 -- v0 just truncates)
 *  - Auto-dismiss timer runs per visible card; ticks every second
 *    so a 5-second Info doesn't sit stale on a paused machine
 */
@Composable
fun NotificationStack(center: NotificationCenter = koinInject()) {
    val groups by center.groups.collectAsState()

    // Drives the "Now / 5s / 1m" relative-time display + auto-dismiss
    // timers below. One-second tick is precise enough; the human eye
    // can't tell the difference between 4s and 5s on a toast.
    var clockTick by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            clockTick = Instant.now()
        }
    }

    // Auto-dismiss: for each visible group, if its latest event's
    // severity has an autoDismissAfter window and the event is older
    // than that, dismiss the whole group. The next render won't see
    // it. We do this BEFORE drawing so the user does not see a card
    // that should have already gone.
    LaunchedEffect(clockTick, groups) {
        groups.forEach { group ->
            val window = group.severity.autoDismissAfter ?: return@forEach
            val age = java.time.Duration.between(group.latest.createdAt, clockTick)
            if (age.toMillis() >= window.inWholeMilliseconds) {
                center.dismiss(group.sourceKey)
            }
        }
    }

    if (groups.isEmpty()) return

    Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp)) {
        Column(
            modifier              = Modifier.align(Alignment.TopEnd).widthIn(max = 440.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
        ) {
            val visible = groups.take(MAX_VISIBLE)
            visible.forEach { group ->
                AnimatedVisibility(
                    visible = true,
                    enter   = fadeIn(),
                    exit    = fadeOut(),
                ) {
                    NotificationCard(
                        group     = group,
                        now       = clockTick,
                        onDismiss = { center.dismiss(group.sourceKey) },
                    )
                }
            }
            if (groups.size > MAX_VISIBLE) {
                Text(
                    text     = "+${groups.size - MAX_VISIBLE} more",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp).align(Alignment.End),
                )
            }
        }
    }
}

private const val MAX_VISIBLE = 4
