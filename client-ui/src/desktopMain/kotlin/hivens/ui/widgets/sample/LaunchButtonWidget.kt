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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.launch.LaunchControlMode
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.Companion.controlMode
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.home.new.rememberQuickLaunchTarget
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class LaunchButtonProps(
    // Blank falls back to the localized ready label.
    @PropLabel("widget.home.new.launchbutton.label") val label: String = "",
)

// Big "Continue last" launch tile. Decoupled from the QuickLaunch
// card -- this one is a single full-width tap target with a gradient
// background, no surrounding labels or metadata. Designed to feel
// like a console "press to play" affordance.
@Widget(id = "home.new.launchbutton", displayName = "widget.home.new.launchbutton", propsClass = LaunchButtonProps::class)
@Composable
fun LaunchButtonWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<LaunchButtonProps>()
    val s = LocalStrings.current
    val quickLaunch = rememberQuickLaunchTarget() ?: return
    val target = quickLaunch.target
    val indications: IndicationCenter = koinInject()
    val controller: LauncherController = koinInject()
    val indication by indications.launchIndication(target.id).collectAsState()
    val mode = indication.controlMode()

    // A running game is something this tile can act on, so it stays lit and
    // stops it. Before this it read canLaunch alone and simply went grey for as
    // long as the game was up -- the one state where the big obvious control on
    // the home screen had something useful to offer.
    val ready = when (mode) {
        LaunchControlMode.Stop -> true
        LaunchControlMode.Wait -> false
        LaunchControlMode.Play -> quickLaunch.canLaunch
    }

    val gradient = Brush.linearGradient(
        colors = listOf(
            NxTheme.colors.primary,
            NxTheme.colors.primary.copy(alpha = 0.78f),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (ready) gradient else Brush.linearGradient(listOf(
                NxTheme.colors.surfaceVariant,
                NxTheme.colors.surfaceVariant,
            )))
            .clickable(
                enabled = ready,
                onClick = if (mode == LaunchControlMode.Stop) {
                    { controller.abort() }
                } else {
                    quickLaunch.launch
                },
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = if (ready) 0.18f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(icon = if (mode == LaunchControlMode.Stop) NxIcon.Stop else quickLaunch.icon,
                    contentDescription = null,
                    tint               = if (ready) Color.White else NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // The tile picks its own words -- the mode says what the
                    // control does, not what it is called.
                    text       = when {
                        mode == LaunchControlMode.Stop -> s.packPlayExit
                        mode == LaunchControlMode.Wait -> s.packPlayWait
                        ready -> quickLaunch.buttonLabel ?: p.label.ifBlank { s.launchTileReady }
                        else  -> s.launchTileBlocked
                    },
                    style      = MaterialTheme.typography.titleLarge,
                    color      = if (ready) Color.White else NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = target.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ready) Color.White.copy(alpha = 0.85f)
                            else NxTheme.colors.textSecondary.copy(alpha = 0.7f),
                )
            }
        }
    }
}
