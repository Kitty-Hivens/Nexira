package hivens.ui.widgets.home.new

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import org.koin.compose.koinInject

/**
 * Resolved quick-launch target plus its launch affordance. [canLaunch]
 * is false while no session exists or a launch is already mid-flight;
 * callers gate their button on it and invoke [launch] on tap.
 */
internal class QuickLaunchTarget(
    val target: PackInstance,
    val canLaunch: Boolean,
    val launch: () -> Unit,
)

/**
 * Shared "continue last / launch newest" resolution for the new-home
 * launch widgets. Target = most recently played, else most recently
 * installed. Returns null when the repo is empty (the widget elides
 * itself), so callers should `?: return`.
 */
@Composable
internal fun rememberQuickLaunchTarget(): QuickLaunchTarget? {
    val ctx = LocalHomeNewContext.current
    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())
    val launchState by controller.state.collectAsState()

    val target: PackInstance = remember(all) {
        all.maxByOrNull { it.lastPlayedEpochOrZero }
            ?: all.maxByOrNull { it.createdAtEpoch }
    } ?: return null

    val session = (ctx.appState as? AppState.Authenticated)?.session
    val canLaunch = session != null &&
        (launchState is LaunchState.Idle || launchState is LaunchState.Error)

    return QuickLaunchTarget(
        target = target,
        canLaunch = canLaunch,
        launch = launch@{
            val s = session ?: return@launch
            launchDriver.observe(LaunchTarget.Pack(target))
            controller.launchPackInstance(s, target)
        },
    )
}
