package hivens.ui.notifications.drivers

import hivens.core.data.PackInstance
import hivens.launcher.launch.LaunchError
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.AppStrings
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
    // Read on each push so a locale change in Settings is picked up
    // mid-launch without restarting the driver.
    private val stringsProvider: () -> AppStrings,
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
        val s = stringsProvider()
        indications.setLaunchIndication(pack.id, LaunchIndication.Preparing)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = s.notifPackPreparing(pack.displayName),
            body      = s.notifPackStage(state.stage.name.lowercase()),
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

        val s = stringsProvider()
        val notifProgress: Float = fraction ?: Float.NaN
        val displayPct =
            if (fraction == null) s.notifPackSyncIndeterminate
            else s.notifPackSyncPercent((fraction * 100).toInt())
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = s.notifPackSyncing(pack.displayName),
            body      = s.notifPackSyncBody(state.currentFileIdx, state.totalFiles, displayPct),
            progress  = notifProgress,
        )
    }

    private fun onRunning(pack: PackInstance, state: LaunchState.GameRunning) {
        val s = stringsProvider()
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
            title     = s.notifPackRunning(pack.displayName),
            body      = null,
            actions   = listOf(
                NotifAction("show_console", s.notifActionShowConsole) { gameConsole.show() },
                NotifAction("abort",        s.notifActionStop)        { controller.abort() },
            ),
        )
    }

    private fun onError(pack: PackInstance, reason: LaunchError) {
        val s = stringsProvider()
        indications.setLaunchIndication(pack.id, LaunchIndication.Failed)
        sessions.unregister(pack.id)
        notifications.push(
            sourceKey = sourceKeyFor(pack),
            sender    = pack.displayName,
            iconUrl   = iconUrlFor(pack),
            severity  = Severity.Critical,
            kind      = Kind.Sticky,
            title     = s.notifPackFailed(pack.displayName),
            body      = humanReason(reason, s),
            actions   = listOf(
                NotifAction("show_console", s.notifActionShowConsole) { gameConsole.show() },
            ),
        )
    }

    private fun onIdle(pack: PackInstance) {
        val s = stringsProvider()
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
            title     = s.notifPackSessionEnded(pack.displayName),
            body      = null,
        )
    }

    private fun sourceKeyFor(pack: PackInstance): String = "pack:${pack.id}:launch"

    // PackInstance does not carry icon_url yet; returns null until
    // project_pack_rich_metadata propagates summary.icon_url.
    private fun iconUrlFor(pack: PackInstance): String? = null

    private fun humanReason(reason: LaunchError, s: AppStrings): String = when (reason) {
        is LaunchError.ExitCode       -> s.notifReasonExitCode(reason.code)
        is LaunchError.Internal       -> reason.message.ifBlank { null }
                                            ?.let { s.notifReasonInternalDetail(it) }
                                            ?: s.notifReasonInternal
        is LaunchError.AuthFail       -> reason.cause?.ifBlank { null }
                                            ?.let { s.notifReasonAuthFailDetail(it) }
                                            ?: s.notifReasonAuthFail
        LaunchError.OfflineNoClient   -> s.notifReasonOfflineNoClient
        LaunchError.OfflineNoManifest -> s.notifReasonOfflineNoManifest
        LaunchError.TwoFactorExpired  -> s.notifReasonTwoFactorExpired
    }
}
