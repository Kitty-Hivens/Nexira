package hivens.ui.notifications.drivers

import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchError
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.Severity
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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

    // Per-pack de-dup: rapid double-clicks must not stack observers.
    // put-then-cancel-previous is atomic via ConcurrentHashMap.put returning
    // the prior mapping.
    private val observerJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(pack: PackInstance) {
        val job = appScope.launch {
            try {
                // dropWhile-until-Prepare handles BOTH stale-Idle and stale-
                // terminal (Error / GameRunning from a previous launch).
                // launchPackInstance always transitions through Prepare(INIT)
                // first, so the new launch's first state passes the gate.
                //
                // transformWhile-emit-then-stop terminates the flow on the
                // first terminal value seen. `return@launch` from inside a
                // crossinline `collect { }` lambda is prohibited; this is
                // the flow-operator equivalent.
                controller.state
                    .dropWhile { it !is LaunchState.Prepare }
                    .transformWhile { state ->
                        emit(state)
                        state !is LaunchState.Idle && state !is LaunchState.Error
                    }
                    .collect { state ->
                        when (state) {
                            is LaunchState.Prepare     -> onPrepare(pack, state)
                            is LaunchState.Downloading -> onDownloading(pack, state)
                            is LaunchState.GameRunning -> onRunning(pack, state)
                            is LaunchState.Error       -> onError(pack, state.reason)
                            LaunchState.Idle           -> onIdle(pack)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("PackLaunchDriver observation aborted for ${pack.id}", e)
                indications.setLaunchIndication(pack.id, null)
                sessions.unregister(pack.id)
            }
        }
        observerJobs.put(pack.id, job)?.cancel()
    }

    private fun onPrepare(pack: PackInstance, state: LaunchState.Prepare) {
        indications.setLaunchIndication(pack.id, LaunchIndication.Preparing)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = "Preparing ${pack.displayName}",
            body      = "Stage: ${state.stage.name.lowercase()}",
            progress  = state.progress.coerceIn(0f, 1f),
        )
    }

    private fun onDownloading(pack: PackInstance, state: LaunchState.Downloading) {
        // null = indeterminate per LaunchIndication.Downloading.progress
        // contract; NaN sentinel goes only to NotificationEvent.progress
        // where the renderer branches on isNaN.
        val fraction: Float? = when {
            state.totalBytes > 0L      -> state.downloadedBytes.toFloat() / state.totalBytes
            state.downloadedBytes > 0L -> null
            else                       -> 0f
        }
        indications.setLaunchIndication(pack.id, LaunchIndication.Downloading(fraction))

        val notifProgress: Float = fraction ?: Float.NaN
        val displayPct =
            if (fraction == null) "downloading..." else "${(fraction * 100).toInt()}%"
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = "Syncing ${pack.displayName}",
            body      = "${state.currentFileIdx}/${state.totalFiles} files, $displayPct",
            progress  = notifProgress,
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
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Success,
            kind      = Kind.ActionRequired,
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
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Critical,
            kind      = Kind.Sticky,
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
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Success,
            kind      = Kind.OneShot,
            title     = "${pack.displayName} session ended",
            body      = null,
        )
    }

    private fun sourceKeyFor(pack: PackInstance): String = "pack:${pack.id}:launch"

    // PackInstance does not carry icon_url yet; returns null until
    // project_pack_rich_metadata propagates summary.icon_url.
    private fun iconUrlFor(pack: PackInstance): String? = null

    private fun humanReason(reason: LaunchError): String = when (reason) {
        is LaunchError.ExitCode       -> "Game exited with code ${reason.code}"
        is LaunchError.Internal       -> reason.message.ifBlank { "Internal error" }
        is LaunchError.AuthFail       -> reason.cause?.ifBlank { null } ?: "Authentication failed"
        LaunchError.OfflineNoClient   -> "Pack files missing on disk"
        LaunchError.OfflineNoManifest -> "No cached manifest; go online once to sync"
        LaunchError.TwoFactorExpired  -> "Sign in again to refresh credentials"
    }
}
