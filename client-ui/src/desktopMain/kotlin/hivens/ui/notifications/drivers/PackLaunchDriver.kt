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

/**
 * Bridge between [LauncherController] launch state and the three
 * notification-system surfaces. One driver instance per process; the
 * UI calls [observe] right after invoking
 * [LauncherController.launchPackInstance] and the driver mirrors
 * every state transition until the controller settles back to Idle
 * (or hits Error).
 *
 * Why a per-launch observer instead of a single global subscription
 * to `controller.state`: the controller's state is generic (Prepare /
 * Downloading / GameRunning / Error) and doesn't carry the pack
 * identity. Tying the observation lifecycle to the click that
 * triggered the launch lets us key every notification / indication /
 * session entry on the pack the user actually started, without
 * widening the controller's contract.
 *
 * The controller's re-entry guard prevents concurrent launches, so
 * at most one launch is observed at any time -- starting a second
 * launch while the first is still in flight is rejected at the
 * controller level before this driver sees anything.
 */
class PackLaunchDriver(
    private val controller: LauncherController,
    private val notifications: NotificationCenter,
    private val indications: IndicationCenter,
    private val sessions: SessionRegistry,
    private val gameConsole: GameConsoleService,
    private val appScope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PackLaunchDriver::class.java)

    /**
     * Begin observing the controller until the launch initiated for
     * [pack] completes (Idle) or fails (Error). Safe to call before
     * the click that triggers the launch reaches the controller --
     * the observer waits for the first non-Idle state before
     * binding, so a stale Idle from a previous launch does not
     * cause a phantom completion event.
     */
    fun observe(pack: PackInstance) {
        appScope.launch {
            try {
                // Wait for the click's launchPackInstance to actually
                // flip state away from Idle. Without this guard the
                // first collect tick would see Idle (the residual
                // state from any previous run) and the driver would
                // immediately think the new launch finished.
                controller.state.first { it !is LaunchState.Idle }

                // Now mirror every transition. The collect ends when
                // we hit a terminal state and explicitly return.
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
            packIconUrl     = null,  // wired when [[project_pack_rich_metadata]] lands
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
        // Idle reached after a non-Idle pass = the launch completed
        // cleanly (exit code 0 path). Tear down the active session
        // and let the indication fade. The notification group keeps
        // its history so the user can scroll back through the run.
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
        // PackInstance does not yet carry icon_url; once
        // [[project_pack_rich_metadata]] propagates the cached
        // summary url into PackInstance, swap to AvatarSource.Url.
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
