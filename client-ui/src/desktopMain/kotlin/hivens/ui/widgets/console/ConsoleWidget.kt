package hivens.ui.widgets.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.screens.ConsoleContent
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.ConsoleAlertLevel
import hivens.ui.utils.ConsoleAlertState
import hivens.ui.utils.ConsoleSettingsManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * Right-rail console host. Two-state composable: a compact badge that
 * surfaces WARN / ERROR counts on normal sessions, and an expanded
 * inline view that embeds the full `ConsoleContent` composable. Auto-
 * expands on [ConsoleAlertLevel.Critical] (driven by LaunchDriver on
 * abnormal exit); user can collapse manually after diagnosing.
 *
 * Source of truth stays `GameConsoleService` (shared singleton with the
 * standalone `ConsoleWindow`). Settings flow through
 * `ConsoleSettingsManager` so per-host preferences write back to the
 * same `console.json`.
 */
@Widget(id = "appshell.rightrail.console", displayName = "Console", removable = true)
@Composable
fun ConsoleWidget(instance: WidgetInstance) {
    val gameConsole: GameConsoleService = koinInject()
    val alertState: ConsoleAlertState = koinInject()
    val dataDirectory: Path = koinInject()
    val json: kotlinx.serialization.json.Json = koinInject()

    val settingsManager = remember { ConsoleSettingsManager(dataDirectory, json) }
    var settings by remember { mutableStateOf(settingsManager.load()) }

    var expanded by remember { mutableStateOf(false) }
    val alert by alertState.level.collectAsState()

    // Auto-expand on Critical -- mirrors the user-confirmed hybrid
    // default: rail-rest under normal use, immediate diagnostics on
    // crash. Clearing the alert here keeps the widget from re-expanding
    // every recomposition.
    LaunchedEffect(alert) {
        if (alert == ConsoleAlertLevel.Critical && !expanded) {
            expanded = true
            alertState.clear()
        }
    }

    // Coalesce log-size changes (modded MC startup floods thousands of
    // lines in seconds) so the badge counts don't thrash. One refresh
    // per frame is plenty for a peripheral indicator.
    var logTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { gameConsole.logs.size }
            .conflate()
            .distinctUntilChanged()
            .collect { logTick = it }
    }
    val warnCount = remember(logTick) { gameConsole.logs.count { it.type == LogType.WARN } }
    val errorCount = remember(logTick) { gameConsole.logs.count { it.type == LogType.ERROR } }

    Column(Modifier.fillMaxWidth()) {
        ConsoleBadge(
            expanded   = expanded,
            warnCount  = warnCount,
            errorCount = errorCount,
            alert      = alert,
            onClick    = {
                expanded = !expanded
                if (expanded) alertState.clear()
            },
        )
        if (expanded) {
            HorizontalDivider(
                thickness = 1.dp,
                color     = CelestiaTheme.colors.outline.copy(alpha = 0.3f),
            )
            // Fixed 360 dp inline panel: enough vertical room for the
            // toolbar + ~20 log lines + footer, without the widget
            // taking over the rail entirely. Slated for refinement when
            // Phase G slot-weights land -- a widget could ask for
            // weight = 1f and grow to fill the rail's remaining space.
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                ConsoleContent(
                    settings = settings,
                    onSettingsChange = { new ->
                        settings = new
                        settingsManager.save(new)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConsoleBadge(
    expanded: Boolean,
    warnCount: Int,
    errorCount: Int,
    alert: ConsoleAlertLevel,
    onClick: () -> Unit,
) {
    val colors = CelestiaTheme.colors
    val backgroundTint = when (alert) {
        ConsoleAlertLevel.Critical -> colors.criticalAccent.copy(alpha = 0.18f)
        ConsoleAlertLevel.Warn     -> colors.warnAccent.copy(alpha = 0.12f)
        ConsoleAlertLevel.None     -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundTint)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Default.Terminal,
            contentDescription = null,
            tint               = colors.textSecondary,
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text       = "console",
            color      = colors.textPrimary,
            fontSize   = 11.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        if (warnCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text       = "W $warnCount",
                color      = colors.warnAccent,
                fontSize   = 10.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (errorCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text       = "E $errorCount",
                color      = colors.criticalAccent,
                fontSize   = 10.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f, fill = true))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Icon(
                imageVector        = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint               = colors.textSecondary,
                modifier           = Modifier.size(14.dp),
            )
        }
    }
}
