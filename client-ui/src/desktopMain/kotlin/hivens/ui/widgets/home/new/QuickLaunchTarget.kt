package hivens.ui.widgets.home.new

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import hivens.auth.OfflineAuthProvider
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.nx.PlayButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * What the home launch button offers. Signed in it plays; signed out it
 * degrades to the offline launch (when an offline name is configured) or a
 * route to sign-in -- there is always a live affordance, never a dead button.
 */
internal enum class LaunchAffordance { Play, PlayOffline, GoSignIn }

internal fun launchAffordance(hasSession: Boolean, offlineName: String?): LaunchAffordance = when {
    hasSession -> LaunchAffordance.Play
    !offlineName.isNullOrBlank() -> LaunchAffordance.PlayOffline
    else -> LaunchAffordance.GoSignIn
}

/**
 * Resolved quick-launch target plus its affordance. [canLaunch] is false
 * while a launch is already mid-flight; callers gate their button on it and
 * invoke [launch] on tap. [buttonLabel] overrides the caller's default play
 * label for the degraded affordances (offline / sign-in); null keeps it.
 */
internal class QuickLaunchTarget(
    val target: PackInstance,
    val affordance: LaunchAffordance,
    val canLaunch: Boolean,
    val buttonLabel: String?,
    val icon: IconKey,
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
    val offlineProvider: OfflineAuthProvider = koinInject()
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())
    val launchState by controller.state.collectAsState()

    val target: PackInstance = remember(all) {
        all.maxByOrNull { it.lastPlayedEpochOrZero }
            ?: all.maxByOrNull { it.createdAtEpoch }
    } ?: return null

    val session = (ctx.appState as? AppState.Authenticated)?.session
    // Only consulted while signed out, so the brief null before the IO read
    // resolves at worst flashes the sign-in affordance once.
    val offlineName by produceState<String?>(null, session == null) {
        if (session == null) {
            value = withContext(Dispatchers.IO) { settingsService.getSettings().offlinePlayerName }
        }
    }
    val idle = launchState is LaunchState.Idle || launchState is LaunchState.Error

    return when (launchAffordance(session != null, offlineName)) {
        LaunchAffordance.Play -> QuickLaunchTarget(
            target = target,
            affordance = LaunchAffordance.Play,
            canLaunch = idle,
            buttonLabel = null,
            icon = NxIcon.PlayArrow,
            launch = launch@{
                val real = session ?: return@launch
                launchDriver.observe(LaunchTarget.Pack(target))
                controller.launchPackInstance(real, target)
            },
        )
        LaunchAffordance.PlayOffline -> QuickLaunchTarget(
            target = target,
            affordance = LaunchAffordance.PlayOffline,
            canLaunch = idle,
            buttonLabel = s.loginPlayOffline,
            icon = NxIcon.PlayArrow,
            launch = {
                // Same offline identity LoginPanel's button mints; the shipped
                // offline-launch path handles the rest from the cached manifest.
                val name = offlineName
                if (!name.isNullOrBlank()) {
                    scope.launch {
                        val offlineSession = withContext(Dispatchers.IO) { offlineProvider.login(name, "", "") }
                        launchDriver.observe(LaunchTarget.Pack(target))
                        controller.launchPackInstance(offlineSession, target)
                    }
                }
            },
        )
        LaunchAffordance.GoSignIn -> QuickLaunchTarget(
            target = target,
            affordance = LaunchAffordance.GoSignIn,
            canLaunch = true,
            buttonLabel = s.loginButton,
            icon = NxIcon.Person,
            launch = { ctx.onScreenChange(Screen.Profile) },
        )
    }
}

/**
 * The shared launch pill for the home widgets, walking the same launch states
 * the pack-detail hero does off [IndicationCenter]: a running target turns
 * into Exit (stops the game), prepare/sync shows the inert wait, and otherwise
 * it plays / offline-plays / routes to sign-in per the resolved [quickLaunch]. Keeps the
 * one launch affordance in one place instead of each widget re-deriving it.
 *
 * [defaultLabel] is the play label the widget wants when the affordance carries
 * none (the resolver overrides it only for the offline / sign-in cases).
 */
@Composable
internal fun QuickLaunchButton(
    quickLaunch: QuickLaunchTarget,
    defaultLabel: String,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    val s = LocalStrings.current
    val indications: IndicationCenter = koinInject()
    val controller: LauncherController = koinInject()
    val indication by indications.launchIndication(quickLaunch.target.id).collectAsState()

    val busy = indication is LaunchIndication.Preparing || indication is LaunchIndication.Downloading
    val running = indication is LaunchIndication.Running

    PlayButton(
        label    = when {
            running -> s.packPlayExit
            busy    -> s.packPlayWait
            else    -> quickLaunch.buttonLabel ?: defaultLabel
        },
        icon     = if (running) NxIcon.Stop else quickLaunch.icon,
        busy     = busy,
        onClick  = if (running) { { controller.abort() } } else quickLaunch.launch,
        enabled  = if (running) true else quickLaunch.canLaunch,
        iconOnly = iconOnly,
        modifier = modifier,
    )
}
