package hivens.ui.notifications.drivers

import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchError
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.notifications.AvatarSource
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

// Per-launch observer rather than global controller.state subscription:
// LaunchState doesn't carry pack identity, and binding the observer to
// the click that started the launch is how we key the resulting
// notification/indication/session entries on the right pack.
class PackLaunchDriver(
    private val controller: LauncherController,
    private val notifications: NotificationCenter,
    private val indications: IndicationCenter,
    private val sessions: SessionRegistry,
    private val gameConsole: GameConsoleService,
    private val appScope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PackLaunchDriver::class.java)

    fun observe(pack: PackInstance) {
        appScope.launch {
            try {
                // Wait for non-Idle so a residual Idle from a previous
                // launch doesn't trigger a phantom completion.
                controller.state.first { it !is LaunchState.Idle }

                controller.state.collect { state ->
                    when (state) {
                        is LaunchState.Prepare       -> onPrepare(pack, state)
                        is LaunchState.Downloading   -> onDownloading(pack, state)
                        is LaunchState.GameRunning   -> onRunning(pack, state)
                        is LaunchState.Error         -> {
                            onError(pack, state.reason)
                            return@collect
                        }
                        LaunchState.Idle             -> {
                            onIdle(pack)
                            return@collect
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("PackLaunchDriver observation aborted for ${pack.id}", e)
                indications.setLaunchIndication(pack.id, null)
                sessions.unregister(pack.id)
            }
        }
    }

    private fun onPrepare(pack: PackInstance, state: LaunchState.Prepare) {
        indications.setLaunchIndication(pack.id, LaunchIndication.Preparing)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            avatar    = avatarFor(pack),
            severity  = hivens.ui.notifications.Severity.Progress,
            title     = "Preparing ${pack.displayName}",
            body      = "Stage: ${state.stage.name.lowercase()}",
            progress  = state.progress.coerceIn(0f, 1f),
        )
    }

    private fun onDownloading(pack: PackInstance, state: LaunchState.Downloading) {
        val fraction = if (state.totalBytes > 0L) {
            state.downloadedBytes.toFloat() / state.totalBytes
        } else if (state.downloadedBytes > 0L) {
            Float.NaN  // bytes flowing but size unknown
        } else {
            0f
        }
        indications.setLaunchIndication(pack.id, LaunchIndication.Downloading(fraction))

        val displayPct =
            if (fraction.isNaN()) "downloading..." else "${(fraction * 100).toInt()}%"
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            avatar    = avatarFor(pack),
            severity  = hivens.ui.notifications.Severity.Progress,
            title     = "Syncing ${pack.displayName}",
            body      = "${state.currentFileIdx}/${state.totalFiles} files, $displayPct",
            progress  = fraction,
        )
    }

    private fun onRunning(pack: PackInstance, state: LaunchState.GameRunning) {
        indications.setLaunchIndication(pack.id, LaunchIndication.Running)
        sessions.register(
            packInstanceId  = pack.id,
            packDisplayName = pack.displayName,
            packIconUrl     = null,
            abort           = { controller.abort() },
            showConsole     = { gameConsole.show() },
        )
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            avatar    = avatarFor(pack),
            severity  = hivens.ui.notifications.Severity.Success,
            title     = "${pack.displayName} is running",
            body      = null,
            actions   = listOf(
                NotifAction("show_console", "Show console") { gameConsole.show() },
                NotifAction("abort", "Stop") { controller.abort() },
            ),
        )
    }

    private fun onError(pack: PackInstance, reason: LaunchError) {
        indications.setLaunchIndication(pack.id, LaunchIndication.Failed)
        sessions.unregister(pack.id)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            avatar    = avatarFor(pack),
            severity  = hivens.ui.notifications.Severity.Critical,
            title     = "${pack.displayName} failed to launch",
            body      = humanReason(reason),
            actions   = listOf(
                NotifAction("show_console", "Show console") { gameConsole.show() },
            ),
        )
    }

    private fun onIdle(pack: PackInstance) {
        // Idle after non-Idle = clean exit (code 0). Group history stays
        // so the user can scroll back through the run.
        indications.setLaunchIndication(pack.id, null)
        sessions.unregister(pack.id)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            avatar    = avatarFor(pack),
            severity  = hivens.ui.notifications.Severity.Success,
            title     = "${pack.displayName} session ended",
            body      = null,
        )
    }

    private fun sourceKeyFor(pack: PackInstance): String = "pack:${pack.id}:launch"

    private fun avatarFor(pack: PackInstance): AvatarSource =
        // PackInstance does not carry icon_url yet; swap to Url when it does.
        AvatarSource.Generic

    private fun humanReason(reason: LaunchError): String = when (reason) {
        is LaunchError.ExitCode       -> "Game exited with code ${reason.code}"
        is LaunchError.Internal       -> reason.message.ifBlank { "Internal error" }
        is LaunchError.AuthFail       -> reason.cause?.ifBlank { null } ?: "Authentication failed"
        LaunchError.OfflineNoClient   -> "Pack files missing on disk"
        LaunchError.OfflineNoManifest -> "No cached manifest; go online once to sync"
        LaunchError.TwoFactorExpired  -> "Sign in again to refresh credentials"
    }
}
