package hivens.ui.notifications.drivers

import hivens.auth.OfflineAuthProvider
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchLogEvent
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
    // Where "this launch needs a code" is parked for the shell to answer.
    private val twoFactorGate: hivens.ui.notifications.TwoFactorLaunchGate,
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
            // The session warnings ride LauncherController.events, not its
            // state: a refresh that failed does not change the launch state at
            // all -- that is the whole problem being fixed. Cancelled in the
            // finally below, since collecting a SharedFlow never completes on
            // its own and would keep this job alive past the launch.
            val sessionWarnings = launch {
                controller.events.collect { event -> onLaunchEvent(target, event) }
            }
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
                // Cancellation is the ORDINARY way an observer ends -- starting a
                // second launch cancels every prior one a few lines below. Letting
                // it rethrow past the cleanup left this target's activity entry as
                // Running for the rest of the session: an in-flight entry is never
                // evicted by age, and the surface only offers Dismiss once a phase
                // is terminal, so nothing could ever remove it.
                activities.dismiss(launchKey(target))
                throw e
            } catch (e: Exception) {
                log.warn("LaunchDriver observation aborted for ${target.id}", e)
                indications.setLaunchIndication(target.id, null)
                sessions.unregister(target.id)
                // The observer died, not the work. Whatever the registry is
                // narrating for this target is no longer being updated, so
                // drop it rather than leave a frozen measure on screen.
                activities.dismiss(launchKey(target))
            } finally {
                sessionWarnings.cancel()
            }
        }
        observerJobs.put(target.id, job)?.cancel()
    }

    /**
     * Says out loud that the launch is going ahead on a session it could not
     * refresh. Nothing here stops the launch: the old token is frequently still
     * valid, and the rejection -- when it does come -- arrives as the game's own
     * "Failed to verify username", which names neither the launcher nor the
     * session and leaves the player reconnecting at random until it works.
     */
    private fun onLaunchEvent(target: LaunchTarget, event: LaunchLogEvent) {
        val s = stringsProvider()
        if (event is LaunchLogEvent.ForeignContentRemoved) {
            // Its own group and sticky: a mod that vanished without a word reads as
            // the launcher breaking the pack. Naming the files is what separates
            // "your added jar was removed" from "something ate my install".
            notifications.push(
                sourceKey = "content:${target.id}",
                sender    = target.displayName,
                iconUrl   = target.iconUrl,
                severity  = Severity.Warn,
                kind      = Kind.Sticky,
                title     = s.notifForeignContentRemovedTitle(event.paths.size),
                body      = event.paths.joinToString(", "),
            )
            return
        }
        if (event is LaunchLogEvent.InstanceUnverified) {
            // Critical and sticky, and it names the way out: the launch looks normal
            // until the player tries to join a server, and nothing else on screen
            // would tell them why they were refused.
            notifications.push(
                sourceKey = "content:${target.id}",
                sender    = target.displayName,
                iconUrl   = target.iconUrl,
                severity  = Severity.Critical,
                kind      = Kind.Sticky,
                title     = s.notifInstanceUnverifiedTitle,
                body      = s.notifInstanceUnverifiedBody,
            )
            return
        }
        val (severity, body) = staleSessionWarning(event, s) ?: return
        notifications.push(
            // Own group rather than target.sourceKey: a launch that goes on to
            // fail pushes its own Critical entry, and folding the two would let
            // whichever landed last speak for both.
            sourceKey = "auth:${target.id}",
            sender    = target.displayName,
            iconUrl   = target.iconUrl,
            severity  = severity,
            // Sticky: it is read after the game window has taken focus, so an
            // auto-dismissing toast would expire behind it unseen.
            kind      = Kind.Sticky,
            title     = s.notifSessionStaleTitle,
            body      = body,
        )
    }

    private fun onPrepare(target: LaunchTarget, state: LaunchState.Prepare) {
        val s = stringsProvider()
        indications.setLaunchIndication(target.id, LaunchIndication.Preparing)
        reportActivity(
            target,
            ActivityKind.Launch,
            ActivityPhase.Running(0, 0, state.stage.name.lowercase()),
            actions = setOf(ActivityAction.Cancel),
        )
        // Live progress is the activity surface's job; the notification
        // centre keeps outcomes, which are what its history is for.
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
            actions = setOf(ActivityAction.Cancel),
        )

        val s = stringsProvider()
        val notifProgress: Float = fraction ?: Float.NaN
        val displayPct =
            if (fraction == null) s.notifPackSyncIndeterminate
            else s.notifPackSyncPercent((fraction * 100).toInt())
        // Live progress is the activity surface's job; the notification
        // centre keeps outcomes, which are what its history is for.
    }

    private fun onRunning(target: LaunchTarget, state: LaunchState.GameRunning) {
        val s = stringsProvider()
        indications.setLaunchIndication(target.id, LaunchIndication.Running)
        // The launch is over, so its entry goes. A running game is not this
        // surface's business: it is a state the user can see out of the window,
        // and it already has its places -- the pack hero's control, the session
        // list, the console. The activity surface narrates work in progress.
        activities.dismiss(launchKey(target))
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
        if (reason == LaunchError.TwoFactorExpired) {
            // Not a failure to report -- a step the launch is waiting on. The shell
            // prompts, and the fresh session comes back here to start the same target
            // again, so the player clicks Play once and types a code once.
            indications.setLaunchIndication(target.id, null)
            activities.dismiss(launchKey(target))
            twoFactorGate.request(target.displayName) { session ->
                appScope.launch {
                    observe(target)
                    when (target) {
                        is LaunchTarget.Pack -> controller.launchPackInstance(session, target.instance)
                        is LaunchTarget.Server -> controller.launch(session, target.server)
                    }
                }
            }
            return
        }
        indications.setLaunchIndication(target.id, LaunchIndication.Failed)
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

    private fun reportActivity(
        target: LaunchTarget,
        kind: ActivityKind,
        phase: ActivityPhase,
        actions: Set<ActivityAction> = emptySet(),
    ) {
        activities.report(
            key     = launchKey(target),
            kind    = kind,
            title   = target.displayName,
            iconUrl = target.iconUrl,
            phase   = phase,
            actions = actions,
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

/**
 * Severity + body for a launch event that leaves the session unrefreshed, or
 * null for every event that says nothing about the session.
 *
 * A rejection and a missing password both rate [Severity.Critical]: the server
 * has already decided, and the game's join will be told the same thing. An
 * unreachable auth server is a [Severity.Warn] because the token being carried
 * may well still be accepted -- nothing judged it.
 *
 * Top-level rather than a driver method so it can be exercised without the
 * driver's DI graph, which is the same reason the flow contract is tested apart
 * from the driver in `LaunchDriverTest`.
 */
internal fun staleSessionWarning(event: LaunchLogEvent, s: AppStrings): Pair<Severity, String>? = when (event) {
    is LaunchLogEvent.AuthFailed -> when (event.cause) {
        AuthRefreshFailure.Rejected    -> Severity.Critical to s.notifSessionStaleRejected
        AuthRefreshFailure.Unreachable -> Severity.Warn to s.notifSessionStaleUnreachable
        AuthRefreshFailure.Unknown     -> Severity.Warn to s.notifSessionStaleUnknown
    }
    // No saved password means no refresh ever happens, so this one is not a
    // transient miss -- it is the state the account is in.
    is LaunchLogEvent.NoPassword -> Severity.Critical to s.notifSessionStaleNoPassword
    else -> null
}
