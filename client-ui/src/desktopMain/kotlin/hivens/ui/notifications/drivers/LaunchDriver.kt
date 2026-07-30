package hivens.ui.notifications.drivers

import hivens.auth.OfflineAuthProvider
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.AppStrings
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.Kind
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.Severity
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

// Per-launch observer rather than a global controller.state subscription:
// LaunchState carries no target identity, so binding the observer to the
// click that started the launch is how we key the resulting
// notification / indication / session entries on the right [LaunchTarget].
// One observer for both pack launches (LaunchTarget.Pack) and SC server
// launches (LaunchTarget.Server) -- the controller exposes the same state
// shape for both, and the target abstraction normalises the (id, label,
// icon, source-key) tuple.
class LaunchDriver(
    private val controller: LauncherController,
    private val notifications: NotificationCenter,
    private val indications: IndicationCenter,
    // The single account of what the launcher is doing. This driver is the
    // only place that knows which pack a LaunchState belongs to, so launch
    // and game entries are reported from here rather than from ActivityDriver.
    private val activities: ActivityRegistry,
    private val sessions: SessionRegistry,
    private val gameConsole: GameConsoleService,
    private val appScope: CoroutineScope,
    // Offer-offline-on-failure: mint the fallback identity + resolve its name.
    private val offlineProvider: OfflineAuthProvider,
    private val settingsService: ISettingsService,
    private val credentialStore: ICredentialStore,
    // Read on each push so a locale change in Settings is picked up
    // mid-launch without restarting the driver.
    private val stringsProvider: () -> AppStrings,
) {
    private val log = LoggerFactory.getLogger(LaunchDriver::class.java)

    // Per-target de-dup: rapid double-clicks must not stack observers.
    // put-then-cancel-previous is atomic via ConcurrentHashMap.put returning
    // the prior mapping.
    private val observerJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(target: LaunchTarget) {
        // Single active launch only (the controller enforces it via its
        // launchLock), so at most one observer should ever be live.
        // Cancel EVERY prior observer, not just the same target's: when
        // launch A is aborted and B starts immediately, controller.state
        // can conflate A's terminal Idle into B's Prepare, leaving A's
        // collector open. A stale A-observer would then process B's
        // GameRunning and attach A's dead process stdin sink + register
        // the session under A. Cancelling all prior jobs closes that race.
        observerJobs.values.forEach { it.cancel() }
        observerJobs.clear()
        val job = appScope.launch {
            try {
                // dropWhile-until-Prepare handles BOTH stale-Idle and stale-
                // terminal (Error / GameRunning from a previous launch).
                // Every fresh launch transitions through Prepare(INIT) first,
                // so the new launch's first state passes the gate.
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
                            is LaunchState.Prepare     -> onPrepare(target, state)
                            is LaunchState.Downloading -> onDownloading(target, state)
                            is LaunchState.GameRunning -> onRunning(target, state)
                            is LaunchState.Error       -> onError(target, state.reason)
                            LaunchState.Idle           -> onIdle(target)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("LaunchDriver observation aborted for ${target.id}", e)
                indications.setLaunchIndication(target.id, null)
                sessions.unregister(target.id)
                // The observer died, not the work. Whatever the registry is
                // narrating for this target is no longer being updated, so
                // drop it rather than leave a frozen measure on screen.
                activities.dismiss(launchKey(target))
                activities.dismiss(gameKey(target))
            }
        }
        observerJobs.put(target.id, job)?.cancel()
    }

    private fun onPrepare(target: LaunchTarget, state: LaunchState.Prepare) {
        val s = stringsProvider()
        indications.setLaunchIndication(target.id, LaunchIndication.Preparing)
        reportActivity(target, ActivityKind.Launch, ActivityPhase.Running(0, 0, state.stage.name.lowercase()))
        notifications.push(
            sourceKey = target.sourceKey,
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = s.notifPackPreparing(target.displayName),
            body      = s.notifPackStage(state.stage.name.lowercase()),
            progress  = state.progress.coerceIn(0f, 1f),
        )
    }

    private fun onDownloading(target: LaunchTarget, state: LaunchState.Downloading) {
        // null = indeterminate per LaunchIndication.Downloading.progress
        // contract; NaN sentinel goes only to NotificationEvent.progress
        // where the renderer branches on isNaN.
        val fraction: Float? = when {
            state.totalBytes > 0L      -> state.downloadedBytes.toFloat() / state.totalBytes
            state.downloadedBytes > 0L -> null
            else                       -> 0f
        }
        indications.setLaunchIndication(target.id, LaunchIndication.Downloading(fraction))
        reportActivity(
            target,
            ActivityKind.Launch,
            ActivityPhase.Running(state.downloadedBytes, state.totalBytes),
        )

        val s = stringsProvider()
        val notifProgress: Float = fraction ?: Float.NaN
        val displayPct =
            if (fraction == null) s.notifPackSyncIndeterminate
            else s.notifPackSyncPercent((fraction * 100).toInt())
        notifications.push(
            sourceKey = target.sourceKey,
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = s.notifPackSyncing(target.displayName),
            body      = s.notifPackSyncBody(state.currentFileIdx, state.totalFiles, displayPct),
            progress  = notifProgress,
        )
    }

    private fun onRunning(target: LaunchTarget, state: LaunchState.GameRunning) {
        val s = stringsProvider()
        indications.setLaunchIndication(target.id, LaunchIndication.Running)
        // Preparation is done and the game is up: the launch entry settles and
        // a game entry takes over, narrated by elapsed time rather than a
        // fraction. No Stop control is offered yet -- terminating the process
        // tree is not implemented, and a control that looks live and does
        // nothing is worse than no control.
        activities.dismiss(launchKey(target))
        reportActivity(target, ActivityKind.Game, ActivityPhase.Running(0, 0))
        sessions.register(
            packInstanceId  = target.id,
            packDisplayName = target.displayName,
            packIconUrl     = target.iconUrl,
            abort           = { controller.abort() },
            showConsole     = { gameConsole.show() },
        )
        // Wire command-input -> process stdin while the game is alive.
        // The console UI surfaces an input row once canSendCommands is
        // true; each Enter routes here, writes utf-8 + newline, flushes.
        // Best-effort: IOExceptions on a dead process surface as warn
        // logs without halting the driver. onError / onIdle both
        // detach so a stale sink doesn't outlive the process.
        val stdin = state.handle.stdin
        gameConsole.attachCommandSink { text ->
            try {
                stdin.write((text + "\n").toByteArray(Charsets.UTF_8))
                stdin.flush()
            } catch (e: Exception) {
                log.warn("Failed to send command to game process for ${target.id}", e)
            }
        }
        // Informational only, and it auto-dismisses: the game just appeared on
        // screen, the toast has nothing to ask. Control (console, abort) lives
        // on the session surfaces, not as buttons racing the game for attention.
        notifications.push(
            sourceKey = target.sourceKey,
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = Severity.Success,
            kind      = Kind.OneShot,
            title     = s.notifPackRunning(target.displayName),
            body      = null,
        )
    }

    private fun onError(target: LaunchTarget, reason: LaunchError) {
        val s = stringsProvider()
        indications.setLaunchIndication(target.id, LaunchIndication.Failed)
        activities.dismiss(gameKey(target))
        reportActivity(target, ActivityKind.Launch, ActivityPhase.Failed(humanReason(reason, stringsProvider())))
        sessions.unregister(target.id)
        gameConsole.detachCommandSink()
        notifications.push(
            sourceKey = target.sourceKey,
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = Severity.Critical,
            kind      = Kind.Sticky,
            title     = s.notifPackFailed(target.displayName),
            body      = humanReason(reason, s),
            actions   = buildList {
                add(NotifAction("show_console", s.notifActionShowConsole) { gameConsole.show() })
                // Offer offline for a pack whose online auth failed: offline runs
                // the modpack in singleplayer (an SC-bound pack still can't join
                // its server offline). Only when an offline identity is resolvable.
                if (target is LaunchTarget.Pack && reason.isAuthFailure()) {
                    val instance = target.instance
                    offlineName()?.let { name ->
                        add(NotifAction("play_offline", s.notifActionPlayOffline) {
                            relaunchOffline(instance, name)
                        })
                    }
                }
            },
        )
    }

    /** Auth/credential failures where an offline singleplayer launch is a useful fallback. */
    private fun LaunchError.isAuthFailure(): Boolean = when (this) {
        is LaunchError.MissingAuthProvider -> true
        is LaunchError.AuthlibUnavailable -> true
        LaunchError.TwoFactorExpired -> true
        else -> false
    }

    /** The offline name to reuse: the chosen offline identity, else the saved sign-in. */
    private fun offlineName(): String? =
        settingsService.getSettings().offlinePlayerName?.takeIf { it.isNotBlank() }
            ?: credentialStore.load()?.playerName?.takeIf { it.isNotBlank() }

    private fun relaunchOffline(instance: PackInstance, name: String) {
        appScope.launch {
            val session = offlineProvider.login(name, "", "")
            observe(LaunchTarget.Pack(instance))
            controller.launchPackInstance(session, instance)
        }
    }

    private fun onIdle(target: LaunchTarget) {
        val s = stringsProvider()
        // Idle after non-Idle = clean exit (code 0). Group history stays
        // so the user can scroll back through the run.
        indications.setLaunchIndication(target.id, null)
        activities.dismiss(launchKey(target))
        reportActivity(target, ActivityKind.Game, ActivityPhase.Succeeded)
        sessions.unregister(target.id)
        gameConsole.detachCommandSink()
        notifications.push(
            sourceKey = target.sourceKey,
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = Severity.Success,
            kind      = Kind.OneShot,
            title     = s.notifPackSessionEnded(target.displayName),
            body      = null,
        )
    }

    private fun launchKey(target: LaunchTarget) = "launch:${target.id}"
    private fun gameKey(target: LaunchTarget) = "game:${target.id}"

    private fun reportActivity(target: LaunchTarget, kind: ActivityKind, phase: ActivityPhase) {
        activities.report(
            key     = if (kind == ActivityKind.Game) gameKey(target) else launchKey(target),
            kind    = kind,
            title   = target.displayName,
            iconUrl = target.iconUrl,
            phase   = phase,
        )
    }

    private fun humanReason(reason: LaunchError, s: AppStrings): String = when (reason) {
        is LaunchError.ExitCode            -> s.notifReasonExitCode(reason.code)
        is LaunchError.Internal            -> reason.message.ifBlank { null }
                                                ?.let { s.notifReasonInternalDetail(it) }
                                                ?: s.notifReasonInternal
        is LaunchError.MissingAuthProvider -> s.notifReasonMissingAuthProvider(reason.providerKey)
        is LaunchError.HelperUnavailable   -> s.stateHelperUnavailable(reason.mcVersion)
        is LaunchError.AuthlibUnavailable  -> s.stateAuthlibUnavailable(reason.mcVersion)
        LaunchError.OfflineNoClient        -> s.notifReasonOfflineNoClient
        LaunchError.OfflineNoManifest      -> s.notifReasonOfflineNoManifest
        LaunchError.TwoFactorExpired       -> s.notifReasonTwoFactorExpired
    }
}
