package hivens.ui.widgets.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.SessionRegistry.ActiveSession
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.widget.model.Widget
import org.koin.compose.koinInject
import java.time.Duration

/**
 * The games that are running, one compact entry each, for the title bar: the pack,
 * how long it has been up, and the two things a running game is asked for, its
 * console and a stop.
 *
 * Nothing at all while no game runs, so a bar that carries it looks no different
 * the rest of the time. While arranging it shows its title instead, or it would
 * take no room and there would be nothing to pick up.
 */
@Widget(id = "appshell.topbar.sessions", displayName = "widget.appshell.topbar.sessions")
@Composable
fun TopBarSessionsWidget() {
    val registry: SessionRegistry = koinInject()
    val active by registry.active.collectAsState()
    val s = LocalStrings.current
    if (active.isEmpty()) {
        if (LocalEditMode.current is EditModeState.On) {
            Text(
                text = s.sessionsActiveTitle,
                style = MaterialTheme.typography.labelMedium,
                color = NxInk.quiet,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        active.values.forEach { SessionEntry(it) }
    }
}

@Composable
private fun SessionEntry(session: ActiveSession) {
    val uptime by session.uptime.collectAsState()
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { session.showConsole() }
            .padding(start = 8.dp, end = 2.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(NxColor.status(Status.Success)))
        Spacer(Modifier.width(6.dp))
        Text(
            text = session.packDisplayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = NxInk.main,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = NAME_MAX),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatUptime(uptime),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = NxInk.quiet,
        )
        Spacer(Modifier.width(2.dp))
        BarAction(NxIcon.MenuOpen, s.notifActionShowConsole, NxInk.quiet, session.showConsole)
        BarAction(NxIcon.Stop, s.notifActionStop, NxColor.status(Status.Error), session.abort)
    }
}

/** A 28dp action, the size the bar's height leaves room for. */
@Composable
private fun BarAction(icon: IconKey, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon = icon, contentDescription = label, tint = tint, size = 16.dp)
    }
}

/** A pack name past this gives way to an ellipsis, so two sessions do not push the bar's other lanes off. */
private val NAME_MAX: Dp = 160.dp

internal fun formatUptime(d: Duration): String {
    val totalSeconds = d.seconds
    if (totalSeconds < 60) return "${totalSeconds}s"
    if (totalSeconds < 3600) return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
