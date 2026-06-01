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
import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class QuickLaunchProps(
    @PropLabel("Надпись кнопки") val buttonLabel: String = "",
)

// Quick-launch target = most recently played, falling back to most
// recently installed when nothing has been played. Empty repo elides
// the widget entirely -- HomeNewRecent already shows the install CTA
// in that state and two empty cards would be noisy.
@Widget(id = "home.new.quicklaunch", displayName = "Quick launch", propsClass = QuickLaunchProps::class)
@Composable
fun HomeNewQuickLaunch(instance: WidgetInstance) {
    val p = instance.rememberProps<QuickLaunchProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())
    val launchState by controller.state.collectAsState()

    val target: PackInstance = remember(all) {
        all.maxByOrNull { it.lastPlayedEpochOrZero }
            ?: all.maxByOrNull { it.createdAtEpoch }
    } ?: return
    val session = (ctx.appState as? AppState.Authenticated)?.session

    val canLaunch = session != null &&
        (launchState is LaunchState.Idle || launchState is LaunchState.Error)

    val label = if (target.lastPlayedEpochOrZero > 0L) s.homeQuickContinue else s.homeQuickStart

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
            text       = label,
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
                    text       = target.displayName,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = target.packRef.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val s = session ?: return@Button
                    launchDriver.observe(LaunchTarget.Pack(target))
                    controller.launchPackInstance(s, target)
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
                Text(p.buttonLabel.ifBlank { s.homeQuickButton }, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
