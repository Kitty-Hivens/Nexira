package hivens.ui.widgets.sample

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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.notifications.drivers.PackLaunchDriver
import hivens.ui.theme.CelestiaTheme
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class LaunchButtonProps(
    // Blank falls back to the default "Запустить" label.
    @PropLabel("Надпись") val label: String = "",
)

// Big "Continue last" launch tile. Decoupled from the QuickLaunch
// card -- this one is a single full-width tap target with a gradient
// background, no surrounding labels or metadata. Designed to feel
// like a console "press to play" affordance.
@Widget(id = "home.new.launchbutton", displayName = "Launch button", propsClass = LaunchButtonProps::class)
@Composable
fun LaunchButtonWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<LaunchButtonProps>()
    val ctx = LocalHomeNewContext.current
    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: PackLaunchDriver = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())
    val launchState by controller.state.collectAsState()

    val target: PackInstance = remember(all) {
        all.maxByOrNull { it.lastPlayedEpochOrZero }
            ?: all.maxByOrNull { it.createdAtEpoch }
    } ?: return

    val session = (ctx.appState as? AppState.Authenticated)?.session
    val ready = session != null &&
        (launchState is LaunchState.Idle || launchState is LaunchState.Error)

    val gradient = Brush.linearGradient(
        colors = listOf(
            CelestiaTheme.colors.primary,
            CelestiaTheme.colors.primary.copy(alpha = 0.78f),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (ready) gradient else Brush.linearGradient(listOf(
                CelestiaTheme.colors.surfaceVariant,
                CelestiaTheme.colors.surfaceVariant,
            )))
            .clickable(enabled = ready) {
                val s = session ?: return@clickable
                launchDriver.observe(target)
                controller.launchPackInstance(s, target)
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = if (ready) 0.18f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint               = if (ready) Color.White else CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = if (ready) p.label.ifBlank { "Запустить" } else "Играть нельзя",
                    style      = MaterialTheme.typography.titleLarge,
                    color      = if (ready) Color.White else CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = target.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ready) Color.White.copy(alpha = 0.85f)
                            else CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                )
            }
        }
    }
}
