package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.notifications.drivers.PackLaunchDriver
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.GameConsoleService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

// One-click launch of the most-recently-played pack. Disabled when no
// session is available or the repo has no played packs. While a launch
// is in flight, the button reflects the current state rather than
// firing duplicate launches.
@Widget(id = "home.new.quicklaunch", displayName = "Quick launch")
@Composable
fun HomeNewQuickLaunch(instance: WidgetInstance) {
    val ctx = LocalHomeNewContext.current
    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: PackLaunchDriver = koinInject()
    val gameConsole: GameConsoleService = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())
    val launchState by controller.state.collectAsState()

    val mostRecent = remember(all) {
        all.filter { it.lastPlayedEpochOrZero > 0L }
            .maxByOrNull { it.lastPlayedEpochOrZero }
    } ?: return
    val session = (ctx.appState as? AppState.Authenticated)?.session

    val canLaunch = session != null &&
        (launchState is LaunchState.Idle || launchState is LaunchState.Error)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.45f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = "Быстрый запуск",
            style      = MaterialTheme.typography.labelLarge,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text       = mostRecent.displayName,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = mostRecent.packRef.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val s = session ?: return@Button
                    launchDriver.observe(mostRecent)
                    gameConsole.show()
                    controller.launchPackInstance(s, mostRecent)
                },
                enabled = canLaunch,
                shape   = RoundedCornerShape(10.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Играть", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
