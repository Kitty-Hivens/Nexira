package hivens.ui.notifications.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.notifications.NotificationCenter
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun NotificationStack(center: NotificationCenter = koinInject()) {
    val groups by center.groups.collectAsState()

    // Gate the 1Hz ticker on groups.isNotEmpty -- NotificationStack is
    // mounted in AppShell at all times, so a naive `LaunchedEffect(Unit)`
    // recomposes the subtree every second of idle even when nothing is
    // visible. Keying on the boolean restarts the ticker only when the
    // empty/non-empty transition happens.
    var clockTick by remember { mutableStateOf(Instant.now()) }
    val hasGroups = groups.isNotEmpty()
    LaunchedEffect(hasGroups) {
        if (!hasGroups) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            clockTick = Instant.now()
        }
    }

    LaunchedEffect(clockTick, groups) {
        groups.forEach { group ->
            val window = group.severity.autoDismissAfter ?: return@forEach
            val age = java.time.Duration.between(group.latest.createdAt, clockTick)
            if (age.toMillis() >= window.inWholeMilliseconds) {
                center.dismiss(group.sourceKey)
            }
        }
    }

    if (!hasGroups) return

    val strings = LocalStrings.current
    // Overflow expansion: click "+N more" to show every group instead of
    // just the first MAX_VISIBLE. Stays scoped to this composition --
    // dismissing past the cap rolls back to the collapsed view.
    var overflowExpanded by remember { mutableStateOf(false) }
    val overflow = (groups.size - MAX_VISIBLE).coerceAtLeast(0)
    if (overflow == 0 && overflowExpanded) overflowExpanded = false

    Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp)) {
        // heightIn cap + verticalScroll let the expanded list breathe up to
        // half the screen without pushing the stack off the bottom edge.
        val outerModifier = Modifier
            .align(Alignment.TopEnd)
            .widthIn(max = 440.dp)
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
        Column(
            modifier            = outerModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val visible = if (overflowExpanded) groups else groups.take(MAX_VISIBLE)
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
            if (overflow > 0) {
                val label =
                    if (overflowExpanded) strings.notificationCollapseHistory
                    else strings.notificationShowMore(overflow)
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier   = Modifier
                        .align(Alignment.End)
                        .clickable { overflowExpanded = !overflowExpanded }
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                        }
                        .padding(top = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

private const val MAX_VISIBLE = 4
