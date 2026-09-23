package hivens.launcher.launch

import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.api.interfaces.*
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.toDomain
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.ContentToggle
import hivens.core.data.LauncherLogType
import hivens.core.data.OfflineIdentity
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.core.data.flatten
import hivens.core.diag.ActionRing
import hivens.core.io.InstanceMutationLock
import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import hivens.core.launch.SpawnResult
import hivens.launcher.di.AppCoroutineScopeHook
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.slf4j.MDCContext

/**
 * Constructor injection (not `KoinComponent` + `by inject()`) so the
 * controller is testable without bootstrapping Koin. `singleOf(::LauncherController)`
 * in [hivens.launcher.di.launchPipelineModule] resolves every parameter from the
 * graph automatically; production wiring stays a one-liner.
 *
 * Note: [appScope] is the shared `single<CoroutineScope>(createdAtStart)`
 * registered alongside [AppCoroutineScopeHook], whose JVM shutdown hook cancels
 * the launcher's own work in flight. A running game is not part of that work and
 * survives it; see the hook for why, and [settleSessionForQuit] for how its
 * session is still recorded when the launcher quits around it.
 */
class LauncherController(
    private val authService: AuthProvider,
    private val authProviderRegistry: AuthProviderRegistry,
    private val credentialsManager: ICredentialStore,
    private val settingsService: ISettingsService,
    private val launcherService: ILauncherService,
    private val packRepository: IPackRepository,
    private val smrtPackClient: IMirrorPackClient,
    private val smrtSyncService: IPackSyncService,
    private val dataDirectory: Path,
    private val appScope: CoroutineScope,
    private val work: InstanceWorkRegistry,
) : RunningPackSource {

    private val logger = LoggerFactory.getLogger(LauncherController::class.java)

    /**
     * Persists a pack instance's optional-content [toggles] and re-labels the
     * already-downloaded mods on disk to match -- no network, a flip is just a
     * `.disabled` rename. The caller passes the [manifest] it already loaded for
     * the Content tab. Returns the updated instance for the UI to adopt.
     */
    suspend fun setOptionalMods(
        instance: PackInstance,
        manifest: SmrtPackManifest,
        toggles: List<ContentToggle>,
    ): PackInstance {
        // The one field this owns, set on the record as it stands. The record the
        // caller holds was captured when the tab rendered, and an apply committing in
        // between moves the pinned version, the installed manifest and the cached one.
        // Writing the captured copy back whole restores all three to the build the
        // update had just left, so a checkbox would silently undo an update. The other
        // writers in this file do the same for the same reason.
        val updated = packRepository.update(instance.id) { it.copy(optionalContent = toggles) }
            ?: instance.copy(optionalContent = toggles)
        val clientDir = dataDirectory.resolve("instances").resolve(updated.instanceDirName)
        val deferred = withContext(Dispatchers.IO) {
            InstanceMutationLock.withLock(clientDir) {
                smrtSyncService.relabel(
                    clientDir,
                    manifest.mods,
                    OptionalContentRules.enabledState(manifest.mods, toggles),
                )
            }
        }
        if (deferred.isEmpty()) {
            ActionRing.record("Optional content updated: ${instance.displayName}")
        } else {
            // The selection is saved; only the on-disk flip could not land now (a
            // live holder keeps the jar -- the running game on Windows). It applies
            // on the next launch's sync, so tell the user rather than imply it took.
            ActionRing.record("${instance.displayName}: ${deferred.size} content change(s) apply after the game restarts")
        }
        return updated
    }

    /**
     * Fire-and-forget variant that runs the toggle persistence on the
     * shared [appScope] instead of the caller's. The Content tab's
     * `rememberCoroutineScope` is tied to the composable lifecycle, so
     * a user clicking a checkbox and immediately navigating away
     * cancelled the persistence mid-flight and the toggle silently
     * reverted on next load. The launcher-side scope outlives the UI
     * so the write always reaches disk.
     *
     * Each call carries the WHOLE selection, so a second flip made before the
     * first has landed supersedes it rather than adding to it. The scope is
     * multi-threaded, so without the sequence below the two could land in either
     * order and the older selection could be the one left on disk -- a toggle
     * the user flipped last, silently undone. Superseded calls are dropped, and
     * what is left runs one at a time per instance.
     */
    fun setOptionalModsAsync(
        instance: PackInstance,
        manifest: SmrtPackManifest,
        toggles: List<ContentToggle>,
    ) {
        val latest = optionalWriteSeq.computeIfAbsent(instance.id) { AtomicLong() }.incrementAndGet()
        appScope.launch {
            optionalWriteLocks.computeIfAbsent(instance.id) { Mutex() }.withLock {
                if (optionalWriteSeq[instance.id]?.get() != latest) return@withLock
                setOptionalMods(instance, manifest, toggles)
            }
        }
    }

    /** Per-instance ordering for [setOptionalModsAsync]; see its KDoc. */
    private val optionalWriteSeq = ConcurrentHashMap<String, AtomicLong>()
    private val optionalWriteLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The pack's own `mods/` baseline as filename -> sha1, from the installed
     * manifest recorded at install and after every apply.
     *
     * An optional mod that is off sits beside its canonical name as `.disabled`;
     * it is the same bytes under another name, so both map to the same digest and
     * a toggle does not read as tampering.
     *
     * Null when the instance predates the baseline. The caller then falls back to
     * the roster file, which is weaker -- see [hivens.core.api.interfaces.IPackSyncService.enforceRoster].
     */
    private fun modBaseline(instance: PackInstance): Map<String, String>? {
        val flattened = instance.installedManifest?.flatten() ?: return null
        val mods = flattened.entries
            .filter { it.key.startsWith("mods/") && it.key.count { c -> c == '/' } == 1 }
        if (mods.isEmpty()) return null
        return buildMap {
            for ((path, data) in mods) {
                val name = path.removePrefix("mods/")
                val sha1 = data.sha1
                put(name, sha1)
                put("$name.disabled", sha1)
            }
        }
    }

    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    private val _runningPackInstanceId = MutableStateFlow<String?>(null)

    /**
     * [LaunchState] deliberately carries no target identity -- it is the shared
     * Compose-free contract and a frontend renders it without caring what was
     * launched. This is the separate question "whose files are in use right now",
     * which the settings surfaces need in order to warn about rewriting them and
     * the auto-updater needs in order to leave them alone.
     *
     * Set when a launch is accepted, not when its game spawns: preparing a launch
     * already reads the instance, and an update that started under it would swap
     * files between the check and the game reading them.
     */
    override val runningPackInstanceId: StateFlow<String?> = _runningPackInstanceId.asStateFlow()

    /**
     * Push-side log channel. UI subscribes (`LaunchLogCollector` in
     * `Main.kt`'s application block) and routes each event to the
     * console widget with localization done at the UI layer.
     *
     * Buffer: 256 entries, DROP_OLDEST on overflow. Forge / NeoForge
     * startup can emit hundreds of stdout lines per second; suspending
     * the launch coroutine on a full buffer would block the state
     * machine. The in-memory console buffer in `GameConsoleService` is
     * itself capped at 2000 lines, so lossy-under-pressure semantics
     * stay consistent across the two layers.
     */
    private val _events = MutableSharedFlow<LaunchLogEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LaunchLogEvent> = _events.asSharedFlow()

    private fun emit(event: LaunchLogEvent) {
        _events.tryEmit(event)
    }

    private fun fail(reason: LaunchError, cause: Throwable? = null) {
        _state.value = LaunchState.Error(reason, cause)
        emit(LaunchLogEvent.Error(reason))
    }

    /** Read and written under [launchLock] only. */
    private var launchJob: Job? = null
    /**
     * Tracked separately from [launchJob] so [abort] can terminate the live
     * process even after the coroutine completes the spawn step. Cleared after
     * [LaunchHandle.awaitExit] returns so a later abort() does not try to
     * terminate an already-finished process. Volatile so the abort thread sees
     * the latest write done from the launch coroutine.
     */
    @Volatile private var runningHandle: LaunchHandle? = null
    private val launchLock = Any()

    /**
     * Per-launch abort token. Each launch installs a fresh AtomicBoolean here and
     * captures it in its coroutine; [abort] flips whatever token is current. The
     * launch coroutine, parked in the process wait, checks ITS OWN captured token
     * to tell a user stop from a crash.
     *
     * Per launch rather than one shared flag, because a launch that was stopped
     * during its preparation unwinds after [abort] has already reopened the gate,
     * and a shared flag would be reset under it by the launch that followed.
     */
    @Volatile private var currentAbortToken: AtomicBoolean? = null

    /**
     * Identity of the launch that currently owns the controller's shared state --
     * [runningHandle], [_runningPackInstanceId] and [_state].
     *
     * A launch stopped while it was still preparing is cancelled, and [abort] opens
     * the gate at once, since there is no game to wait for; its coroutine unwinds
     * afterwards, possibly after the next launch has started. Each launch checks
     * that it is still the current one before touching anything shared, and
     * otherwise unwinds silently. A launch with a game running is not reopened
     * until that game exits, so this is no longer the case for a stopped game.
     */
    @Volatile private var currentLaunchTag: Any? = null

    private fun ownsController(tag: Any): Boolean = currentLaunchTag === tag

    /**
     * The session of the game that is up: which launch it belongs to, where its
     * playtime has been counted up to, and what records it.
     *
     * Kept outside the launch coroutine because a session has two ways to end. The
     * usual one is the process exiting, when the coroutine's own tail records it.
     * The other is the launcher quitting with the game left running, where the tail
     * never runs and [settleSessionForQuit] records what has been played so far.
     * Counting from [countedFrom] rather than from a fixed start keeps the two from
     * adding the same minutes twice.
     */
    private class LiveSession(
        val tag: Any,
        val countedFrom: AtomicLong,
        val record: (suspend (sessionSeconds: Long) -> Unit)?,
    ) {
        /** The seconds since the last count, moving the mark to now. */
        fun take(): Long {
            val now = Instant.now().epochSecond
            return (now - countedFrom.getAndSet(now)).coerceAtLeast(0)
        }
    }

    @Volatile private var liveSession: LiveSession? = null

    /**
     * Outcome of a prepare phase. [Ready] carries the path-specific spawn (and
     * an optional post-spawn hook); [Bail] means prepare already called [fail]
     * and the flow must stop WITHOUT overwriting that error state.
     */
    private sealed interface Prepared {
        class Ready(
            val spawn: suspend (onLog: (String, LauncherLogType) -> Unit) -> SpawnResult,
            /**
             * Runs once after the process spawns. [launchInternal] guards it, so it
             * never fails the launch. Returns a job to cancel when the process is
             * gone (a session-long guard), or null when it has nothing to keep.
             */
            val onSpawned: (suspend (handle: LaunchHandle) -> Job?)? = null,
            /** Runs once after the game process exits, with the session length in seconds. Guarded like [onSpawned]. */
            val onExit: (suspend (sessionSeconds: Long) -> Unit)? = null,
            /**
             * Raised by a post-spawn guard that has already called [fail] and ended the
             * process itself. The exit verdict reads it FIRST, because the exit code it
             * would otherwise judge is the one that guard produced.
             *
             * Deliberately not the abort token. With that one set, a non-zero exit is
             * read as a user-requested stop and the state goes to Idle -- which would
             * quietly erase the very error the guard raised. Per-launch by
             * construction: it lives on the value the launch coroutine holds.
             */
            val contentFailed: AtomicBoolean = AtomicBoolean(false),
        ) : Prepared

        data object Bail : Prepared
    }

    /**
     * Owns the launch state machine: the atomic
     * re-entry gate, the per-launch abort token + MDC tag, the spawn, the
     * blocking wait, the exit-code verdict, and the cancellation-vs-crash catch
     * tail. [prepare] runs the path-specific steps and returns a
     * [Prepared.Ready] to spawn or [Prepared.Bail] to stop.
     */
    private fun launchInternal(
        label: String,
        onStart: () -> Unit,
        /** Runs under the gate, once this launch is accepted, before anything else can observe it. */
        onAccepted: () -> Unit = {},
        prepare: suspend CoroutineScope.() -> Prepared,
    ): Boolean {
        // Tag every log line for this attempt with a stable launchId so a user
        // dump can be sliced per-play-click (`grep launchId=abcd1234 *.log`).
        // MDCContext (from kotlinx-coroutines-slf4j) propagates it across every
        // dispatcher hop the flow takes, including LauncherService.
        val launchId = UUID.randomUUID().toString().take(8)
        val abortToken = AtomicBoolean(false)
        val launchTag = Any()
        val job: Job

        // The gate, the fields that identify this launch, and its job are claimed in
        // one step under the lock that [abort] also takes. Two parallel callers (a UI
        // double-click, a tray launch racing one from the Library) must not both pass
        // the gate. And the identity must not be written after the lock is released:
        // an abort in that window flipped the previous launch's token and cancelled
        // the previous job, and this launch then ran on to a game the person believed
        // they had stopped. The job is created lazily inside the lock and started
        // outside it, so the long flow never runs under the gate.
        synchronized(launchLock) {
            if (_state.value !is LaunchState.Idle &&
                _state.value !is LaunchState.Error) return false
            _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
            onAccepted()
            currentAbortToken = abortToken
            currentLaunchTag = launchTag
            job = appScope.launch(MDCContext(mapOf("launchId" to launchId)), start = CoroutineStart.LAZY) {
                runLaunch(label, launchId, launchTag, abortToken, onStart, prepare)
            }
            launchJob = job
        }
        job.start()
        return true
    }

    /** The body of one accepted launch; see [launchInternal]. */
    private suspend fun CoroutineScope.runLaunch(
        label: String,
        launchId: String,
        launchTag: Any,
        abortToken: AtomicBoolean,
        onStart: () -> Unit,
        prepare: suspend CoroutineScope.() -> Prepared,
    ) {
        // Local, not a field: a field would let an aborted launch's tail cancel
        // the guard of the launch that started after it -- the same shape of
        // race currentAbortToken's KDoc describes.
        var sessionGuard: Job? = null
        // The game this launch started, so a stop that interrupts the launch
        // before the wait is armed can still end it.
        var spawned: LaunchHandle? = null
        try {
            _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
            onStart()
            ActionRing.record("Launching: $label (launchId=$launchId)")

            val prepared = when (val r = prepare()) {
                // prepare() already called fail(); stop without touching _state.
                is Prepared.Bail -> {
                    if (ownsController(launchTag)) _runningPackInstanceId.value = null
                    return
                }
                is Prepared.Ready -> r
            }

            setStage(PrepareStage.LAUNCH, 0.95f)
            ActionRing.record("Game running: $label")
            emit(LaunchLogEvent.Launching)

            when (val result = prepared.spawn { text, type -> emit(LaunchLogEvent.ProcessOutput(text, type)) }) {
                // The service maps its own failures (provisioning, spawn IO,
                // SC-binding block) to a semantic LaunchError; surface it.
                is SpawnResult.Failed -> {
                    if (ownsController(launchTag)) _runningPackInstanceId.value = null
                    fail(result.error)
                }
                is SpawnResult.Started -> {
                    val handle = result.handle
                    spawned = handle
                    val live = LiveSession(launchTag, AtomicLong(Instant.now().epochSecond), prepared.onExit)
                    synchronized(launchLock) {
                        if (ownsController(launchTag)) {
                            runningHandle = handle
                            liveSession = live
                            // A stop that arrived while the process was being
                            // started found no game to end. It is ended now,
                            // and the launch waits for it like any other stop.
                            if (abortToken.get()) {
                                _state.value = LaunchState.Stopping(handle)
                                runCatching { handle.terminate() }
                            } else {
                                _state.value = LaunchState.GameRunning(handle)
                            }
                        }
                    }
                    // Post-spawn hook guarded centrally: a throwing hook must
                    // not flip the running game into an Error state.
                    prepared.onSpawned?.let { hook ->
                        runCatching { sessionGuard = hook(handle) }
                            .onFailure { logger.warn("Post-spawn hook failed for {}", label, it) }
                    }

                    // Reads its OWN captured abortToken, never the
                    // currentAbortToken field -- see that field's KDoc.
                    val exitCode = handle.awaitExit()
                    // Whatever the post-spawn guard was still watching for, the
                    // process is gone and there is nothing left to watch it on.
                    // Unconditional: this one is this launch's own.
                    sessionGuard?.cancel()
                    ActionRing.record("Game exited: $label (code $exitCode)")
                    // Recorded before the state settles, and before anything
                    // could reopen the gate: a stop is a normal end of a session
                    // and its playtime counts. It used to be skipped whole,
                    // because the stop cancelled the coroutine and the wait
                    // threw past this tail.
                    live.record?.let { hook ->
                        runCatching { hook(live.take()) }.onFailure { logger.warn("Post-exit hook failed for {}", label, it) }
                    }

                    synchronized(launchLock) {
                        if (ownsController(launchTag)) {
                            runningHandle = null
                            liveSession = null
                            // Cleared here rather than in the pack path's own exit
                            // hook: that hook is guarded and may be skipped, and "no
                            // files are in use" has to be true the moment the
                            // process is gone.
                            _runningPackInstanceId.value = null
                            when {
                                // The watchdog ended this session. Reported now that
                                // the process is gone rather than when it was told to
                                // go: Error reads as Play, and a second game started
                                // inside the grace would share the instance with this
                                // one. Read before the exit code, which is the
                                // watchdog's own doing.
                                prepared.contentFailed.get() -> fail(LaunchError.ContentChangedDuringLaunch)
                                exitCode != 0 && !abortToken.get() -> fail(LaunchError.ExitCode(exitCode))
                                else -> _state.value = LaunchState.Idle
                            }
                        }
                        // A newer launch owns the state otherwise: this one exited
                        // into a world that has moved on and says nothing.
                    }
                }
            }
        } catch (e: Exception) {
            sessionGuard?.cancel()
            // A game the person asked to stop is ended whatever interrupted the
            // wait. Not otherwise: when the launcher itself is shutting down the
            // scope is cancelled with no stop requested, and a game the person
            // chose to leave running on quit has to stay running.
            if (abortToken.get()) spawned?.let { runCatching { it.terminate() } }
            synchronized(launchLock) {
                val mine = ownsController(launchTag)
                if (mine) {
                    runningHandle = null
                    liveSession = null
                    _runningPackInstanceId.value = null
                }
                if (e !is CancellationException) {
                    logger.error("Launch flow failed for {}", label, e)
                    if (mine) fail(LaunchError.Internal(e.message ?: ""), e)
                } else if (mine) {
                    _state.value = LaunchState.Idle
                }
            }
        }
    }

    /**
     * The launch path: a [PackInstance] from the local Library. Re-entry guard,
     * MDC tagging, and abort semantics come from [launchInternal];
     * [preparePackLaunch] supplies the manifest resolve + pack auth + spawn
     * binding.
     *
     * @return false when a launch was already under way and this one was refused.
     */
    fun launchPackInstance(
        currentSession: SessionData,
        packInstance: PackInstance,
    ) = launchInternal(
        label = packInstance.displayName,
        onAccepted = { _runningPackInstanceId.value = packInstance.id },
        onStart = {
            emit(LaunchLogEvent.SessionStarted(packInstance.id, packInstance.displayName))
            emit(LaunchLogEvent.AppBanner)
            // Mirror packs are public read; surfacing the offline flag here
            // would be misleading -- pack-centric Play does not need network at
            // all when cachedManifest is populated.
            emit(LaunchLogEvent.TargetServer(packInstance.displayName, offline = false))
        },
        prepare = { preparePackLaunch(currentSession, packInstance) },
    )

    /**
     * Prepare phase. Skips a per-launch asset re-sync -- mirror packs are static
     * and already on disk after install:
     * - Resolves the [CachedManifestSnapshot]; when [PackInstance.cachedManifest]
     *   is null (instance predates the field) a one-time mirror fetch fills it
     *   and writes it back via [IPackRepository.update].
     * - Refreshes the SC session right before spawn for SC-bound packs so a cold
     *   mod-load (server-side SC tokens age out in ~minutes) does not invalidate
     *   the join. Packs that declare no requirement pass through untouched.
     * - Resolves Java from the manifest's declared major, not a version heuristic.
     *
     * [Prepared.Ready.onSpawned] bumps [PackInstance.lastPlayedEpochOrZero] after
     * a real spawn (never before -- a failed spawn must not pollute the Library's
     * recently-played sort).
     */
    private suspend fun preparePackLaunch(
        currentSession: SessionData,
        packInstance: PackInstance,
    ): Prepared {
        // Before anything reads the instance. The launch controls already say this and
        // do not offer Play, so this is the refusal for a launch that arrives some other
        // way: a notification's relaunch, the second-factor retry, a tray entry.
        work.workOn(packInstance.id)?.let { busy ->
            ActionRing.record("Pack launch ${packInstance.displayName}: refused, the instance is busy (${busy.name})")
            fail(LaunchError.InstanceBusy(busy))
            return Prepared.Bail
        }

        val settings = settingsService.getSettings()

        // 1. Resolve the manifest snapshot. Stored on the instance after
        // install; one-shot fetch + write-back covers instances that predate
        // the field.
        setStage(PrepareStage.SYNC, 0.2f)
        val (manifestSnapshot, refreshedInstance) = resolveOrFetchManifest(packInstance)

        // 2. Local sanity: instance directory must exist before we run a network
        // auth round. A broken instance would otherwise burn an SC login (and on
        // 2FA accounts a prompt) only to bail right after with the same error.
        val clientDir = dataDirectory
            .resolve("instances")
            .resolve(refreshedInstance.instanceDirName)
        if (!Files.exists(clientDir)) {
            fail(LaunchError.OfflineNoClient)
            return Prepared.Bail
        }

        // 3. Auth requirement: refresh the session right before spawn.
        //
        // Three ways a launch ends up without a token, and they share one rule:
        // the game process only gets a session that was earned for THIS launch.
        //
        // - Offline: the pack path used to carry the live session anyway, so an
        //   offline launch handed a working accessToken to the game -- the one
        //   launch where the token buys nothing, since there is no server to join.
        // - Unverified instance: no roster means nothing vouched for what is in
        //   mods/, and a token is exactly what an unvouched-for jar would want.
        // - A refresh that did not go through: covered in preparePackAuth.
        // - No server binding: the manifest names no server, so no session was earned
        //   for one. Covered below, where the token is decided.
        val authRequirement = PackAuthRouter.requirementFor(manifestSnapshot.authRequirement)

        // 2b. Hold the instance to the pack -- but only where a token is at stake.
        // A server-bound pack is the case that matters: we are about to hand the
        // game a session that logs into someone's server, and a jar the pack never
        // named is what that session would be lent to. A pack with no binding has no
        // server and gets no token of ours, so what its owner puts in mods/ is their
        // game and none of our business.
        //
        // Held against the roster written to the instance at sync time, so it answers
        // with no network and an offline launch is covered too.
        // The manifest's OWN declaration, not the router's answer: the router falls
        // back to Microsoft for any mirror pack, so it is true of a solo pack as well
        // and would put every instance under the strict rule again.
        val serverBound = manifestSnapshot.authRequirement != null
        val firstLook = if (serverBound) {
            smrtSyncService.enforceRoster(clientDir, modBaseline(refreshedInstance))
        } else {
            RosterVerdict(verified = true)
        }
        // An instance can fall behind the pack without anyone touching it: a version
        // that could not install some entry is followed by one that can, and what it
        // skipped is still what is on disk. That reads here exactly like tampering,
        // and the launch would go on with no token and no word about why, over files
        // the launcher can simply fetch. So it fetches them and asks again.
        val verdict = if (serverBound && !firstLook.verified && !settings.isOfflineMode) {
            catchUpWithPack(clientDir, refreshedInstance, firstLook)
        } else {
            firstLook
        }
        if (verdict.mismatched.isNotEmpty()) {
            ActionRing.record(
                "Pack launch ${refreshedInstance.displayName}: ${verdict.mismatched.size} file(s) do not match the pack's own bytes",
            )
        }
        if (verdict.unreadable.isNotEmpty()) {
            ActionRing.record(
                "Pack launch ${refreshedInstance.displayName}: ${verdict.unreadable.size} file(s) could not be read to check them",
            )
        }
        if (verdict.removed.isNotEmpty()) {
            ActionRing.record(
                "Pack launch ${refreshedInstance.displayName}: removed ${verdict.removed.size} file(s) absent from the pack",
            )
            emit(LaunchLogEvent.ForeignContentRemoved(verdict.removed))
        }

        var session = currentSession
        if (settings.isOfflineMode || !verdict.verified) {
            if (verdict.verified) {
                emit(LaunchLogEvent.OfflineSkipAuth)
            } else {
                ActionRing.record("Pack launch ${refreshedInstance.displayName}: unverified instance, launching without a token")
                emit(LaunchLogEvent.InstanceUnverified)
            }
            session = session.toOffline()
        } else if (!serverBound) {
            // The guards above are armed by the binding, so the token has to follow the
            // same answer. Handing the session in hand to an unbound launch put a live
            // SmartyCraft token on the command line of a game whose mods/ nobody had
            // checked, and that token is not scoped to one server.
            session = licensedSession() ?: run {
                emit(LaunchLogEvent.UnboundOffline)
                currentSession.toOffline()
            }
        } else {
            setStage(PrepareStage.AUTH, 0.4f)
            session = preparePackAuth(authRequirement, currentSession, refreshedInstance)
                ?: return Prepared.Bail
        }

        // 4. Java override. The launch picks the LOADER-declared Java itself
        // (resolved.javaMajor) from the resolved runtime -- same MC + different
        // loader can need different Java (Cleanroom-1.12.2 -> 25 vs
        // legacy-Forge-1.12.2 -> 8), so the version-keyed heuristic stays out of
        // the controller. We only pass the user's explicit global setting; null
        // means "let the service provision."
        setStage(PrepareStage.JVM, 0.7f)
        val javaOverride: Path? = settings.javaPath
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }

        // 5. Spawn binding handed back to launchInternal.
        val contentFailed = AtomicBoolean(false)
        return Prepared.Ready(
            contentFailed = contentFailed,
            spawn = { onLog ->
                launcherService.launchPackClient(
                    sessionData          = session,
                    // The manifest's own declaration, the same one serverBound reads,
                    // so the service's SC binding and the guards below cannot answer
                    // the binding question two ways.
                    manifest             = manifestSnapshot,
                    runtime              = refreshedInstance.runtime,
                    clientRootPath       = clientDir,
                    javaPathOverride     = javaOverride,
                    adaptiveEnabled      = settings.adaptiveMemoryEnabled,
                    // Redirect authlib away from the Mojang hosts only when the
                    // session being carried is an SC one. Keying this on the
                    // pack's ORIGIN instead put a mirror pack with no auth block
                    // behind the redirect while PackAuthRouter had already
                    // resolved it to Microsoft -- the launch would hand a
                    // Microsoft token to the SC host. Same test the service uses
                    // for its SC binding, so the two cannot disagree.
                    redirectAuthHost     = manifestSnapshot.authRequirement?.scServerId != null,
                    // Same partition the roster sweep uses, and for the same
                    // reason: a bound launch is handed a token, so the loader
                    // hooks it inherits are a way to run code beside it. Taken
                    // from the manifest's own declaration rather than the
                    // effective requirement -- the router answers Microsoft for
                    // every mirror pack, which would seal a solo pack too and
                    // cost its owner MangoHud for nothing.
                    boundLaunch      = serverBound,
                    // Agreement, not validity: a second sweep that had to remove
                    // something would report itself verified afterwards, and the
                    // thing it removed is precisely what arrived after the gate.
                    seal = if (!serverBound || !verdict.verified) null else {
                        {
                            val again = smrtSyncService.enforceRoster(clientDir, modBaseline(refreshedInstance))
                            val agrees = again.verified && again.removed.isEmpty() && again.blocked.isEmpty()
                            if (!agrees) {
                                ActionRing.record(
                                    "Pack launch ${refreshedInstance.displayName}: contents changed between the check and the spawn",
                                )
                            }
                            agrees
                        }
                    },
                    // Auth mechanism for an SC-bound join: the redirect agent
                    // (default on) and/or SC's patched authlib jar (default off,
                    // fallback). Both no-op on non-SC packs.
                    useNetworkAgent       = settings.useNetworkAgent,
                    useSmartycraftAuthLib = settings.useSmartycraftAuthLib,
                    displayName          = refreshedInstance.displayName,
                    onLog                = onLog,
                )
            },
            onSpawned = { handle ->
                // The one field this owns, set on the record as it stands. The record in
                // hand was captured before the click, and preparing a launch takes long
                // enough (a sign-in, a catch-up repair) for an update or a settings edit
                // to commit meanwhile. Writing the captured copy back whole put all of
                // that back to how it was. Nothing is written when the instance is gone.
                packRepository.update(refreshedInstance.id) { it.copy(lastPlayedEpochOrZero = Instant.now().epochSecond) }
                // Armed for exactly the launches the seal covers. A launch that got
                // no token has nothing to lend to a jar that arrives late, and its
                // owner's `mods/` is their own business.
                if (serverBound && verdict.verified) {
                    watchSessionContent(handle, clientDir, refreshedInstance, contentFailed)
                } else {
                    null
                }
            },
            onExit = { secs ->
                // Added onto the record as it stands (onSpawned wrote lastPlayed, the
                // user may have edited it mid-session), so neither write clobbers the
                // other. Nothing is written when it is gone: an instance deleted while
                // it ran is not resurrected.
                packRepository.update(refreshedInstance.id) { it.copy(playtimeSeconds = it.playtimeSeconds + secs) }
            },
        )
    }

    /**
     * Fetches whatever the instance is missing against its pinned build, then asks
     * the roster again and answers with the second verdict.
     *
     * Reached only when the first look already failed, so the cost lands on the
     * launches that were going to be refused anyway. The repair is measured against
     * the PINNED manifest rather than the mirror's latest, for the same reason the
     * repair button is: this brings the instance up to the build the player has, and
     * measuring against a newer one would turn a launch into an update nobody asked
     * for.
     *
     * Every failure here leaves the first verdict standing. A mirror that cannot be
     * reached, a build the mirror has retired, an entry nobody may serve: none of
     * them is a reason to refuse the launch, because the launch was already going to
     * proceed unverified. What changes is only whether it needed to.
     */
    private suspend fun catchUpWithPack(
        clientDir: Path,
        instance: PackInstance,
        firstLook: RosterVerdict,
    ): RosterVerdict {
        val version = instance.pinnedPackVersion ?: instance.packRef.version
        setStage(PrepareStage.SYNC, 0.25f)
        ActionRing.record(
            "Pack launch ${instance.displayName}: instance does not match the pack, fetching what is missing",
        )
        val repaired = runCatching {
            val manifest = if (version != null) {
                smrtPackClient.fetchManifestVersion(instance.packRef.id, version)
            } else {
                smrtPackClient.fetchManifest(instance.packRef.id)
            }
            val enabled = OptionalContentRules.enabledState(manifest.mods, instance.optionalContent)
            smrtSyncService.verifyAndRepair(clientDir, manifest, enabled) { current, total, path ->
                // The SYNC stage's own sub-range, so the bar moves during what is
                // otherwise a silent wait on a hundred-file walk.
                setStage(PrepareStage.SYNC, 0.25f + 0.25f * (if (total > 0) current.toFloat() / total else 0f))
            }
        }.onFailure {
            logger.warn("Pack launch {}: could not bring the instance in line: {}", instance.displayName, it.toString())
            ActionRing.record("Pack launch ${instance.displayName}: could not fetch what is missing (${it.message ?: "unreachable"})")
        }.getOrNull() ?: return firstLook

        if (repaired.failed.isNotEmpty()) {
            ActionRing.record(
                "Pack launch ${instance.displayName}: ${repaired.failed.size} file(s) still missing after fetching (${repaired.failed.keys.joinToString()})",
            )
        }
        val second = smrtSyncService.enforceRoster(clientDir, modBaseline(instance))
        if (second.verified) {
            ActionRing.record(
                "Pack launch ${instance.displayName}: brought in line with the pack, ${repaired.repaired.size} file(s) restored",
            )
        }
        return second
    }

    /**
     * Holds the instance to the pack for as long as anything added to `mods/` could
     * still be picked up, and ends the session if it stops matching.
     *
     * The pre-spawn seal can only speak for the instant before the process existed;
     * the loader reads `mods/` seconds later, and that gap was unwatched. See
     * [LaunchContentWatchdog] for how the window is covered and why late is safe
     * there and early is not.
     *
     * Ends the session rather than deleting what it found. The jar is open in a
     * running JVM by then, so removing it neither stops the code nor leaves a
     * working install -- what is left to do is take the session away. The instance
     * is reported as it stands, and the repair path is what puts it right.
     */
    private fun watchSessionContent(
        handle: LaunchHandle,
        clientDir: Path,
        instance: PackInstance,
        contentFailed: AtomicBoolean,
    ): Job = appScope.launch {
        val findings = LaunchContentWatchdog(
            sync = smrtSyncService,
            clientDir = clientDir,
            expected = modBaseline(instance),
        ).run()
        if (findings.isEmpty()) return@launch
        // The process this was armed for must still be the controller's live one.
        // An aborted launch stays parked in its blocking wait, so its guard can
        // outlive it and reach a session that started afterwards -- and end that
        // one instead, over findings about an instance nobody is playing.
        if (runningHandle !== handle) {
            logger.info("Content changed on {} after its session ended; nothing to act on", instance.displayName)
            return@launch
        }

        // Raised BEFORE the process is ended, so the exit verdict already sees it
        // when the wait returns and reports this reason instead of an exit code.
        contentFailed.set(true)
        logger.warn("Content changed after the spawn for {}: {}", instance.displayName, findings)
        ActionRing.record(
            "Pack launch ${instance.displayName}: content changed after the spawn, ending the session (${findings.size})",
        )
        // Stopping, not failed, until the process is gone. Failed reads as Play, and
        // the process has up to the termination grace left: a second launch inside
        // it put two games on one instance, one world and one log. The error is
        // raised by the launch's own tail once the game has exited, which is also
        // what emits the line the UI renders for this reason.
        synchronized(launchLock) {
            if (runningHandle === handle) _state.value = LaunchState.Stopping(handle)
        }
        runCatching { handle.terminate() }
    }

    /**
     * Returns the [CachedManifestSnapshot] for [instance], fetching
     * from the mirror and persisting back when the on-disk value is
     * absent. The returned [PackInstance] is the (possibly updated)
     * instance the caller should use for the rest of the launch flow
     * -- never falls back to the input value silently.
     *
     * For instances without a cached manifest the fetch targets the
     * pinned version (`pinnedPackVersion` or `packRef.version`),
     * NOT the mirror's latest. A floating instance (both pins null)
     * picks up whatever the mirror currently serves -- but those are
     * always created post-this-PR, so they already have a cached
     * manifest and never reach this fallback.
     */
    private suspend fun resolveOrFetchManifest(
        instance: PackInstance,
    ): Pair<CachedManifestSnapshot, PackInstance> {
        instance.cachedManifest?.let { return it to instance }

        val pin = instance.pinnedPackVersion ?: instance.packRef.version
        logger.info(
            "Pack {} has no cached manifest; fetching {} (pin={}) from mirror once.",
            instance.id, instance.packRef.id, pin ?: "latest",
        )
        val manifest = if (pin != null) {
            smrtPackClient.fetchManifestVersion(instance.packRef.id, pin)
        } else {
            smrtPackClient.fetchManifest(instance.packRef.id)
        }
        val snapshot = CachedManifestSnapshot(
            minecraftVersion = manifest.minecraft.version,
            loaderName       = manifest.loader.name,
            loaderVersion    = manifest.loader.version,
            javaMajor        = manifest.java.major,
            authRequirement  = manifest.auth?.toDomain(),
        )
        val refreshed = instance.copy(cachedManifest = snapshot)
        // Onto the record as it stands, for the same reason onSpawned re-reads: the
        // fetch is a network round trip, and the copy in hand may be older than it.
        runCatching {
            packRepository.update(instance.id) { it.copy(cachedManifest = snapshot) }
        }.onFailure { logger.warn("Failed to persist cachedManifest for ${instance.id}", it) }
        return snapshot to refreshed
    }

    /**
     * The signed-in Microsoft session, when the provider is registered and has one.
     *
     * The only token an unbound launch may carry: it is the player's own licence,
     * valid wherever they take it, rather than a session minted for somebody's
     * server. Null means the launch goes offline.
     */
    private fun licensedSession(): SessionData? {
        if (!authProviderRegistry.contains(PackAuthRequirement.Microsoft.PROVIDER_KEY)) return null
        return credentialsManager.accountFor(PackAuthRequirement.Microsoft.PROVIDER_KEY)
    }

    /**
     * Pack-side pre-spawn auth for a server-bound pack, dispatched by its
     * [PackAuthRequirement]. A requirement is enforced only for a provider the
     * [authProviderRegistry] can satisfy: SC-bound requirements
     * ([PackAuthRequirement.SmartyCraft], and the SC half of
     * [PackAuthRequirement.Both]) re-auth via [prepareScAuth] when SC is registered.
     * A declared [PackAuthRequirement.Microsoft] whose provider is not registered is
     * advisory: the pack launches, offline, since nothing here holds a session that
     * was earned for it. A newly registered provider activates its gate on its own.
     */
    private suspend fun preparePackAuth(
        requirement: PackAuthRequirement,
        currentSession: SessionData,
        instance: PackInstance,
    ): SessionData? {
        val scSatisfiable = authProviderRegistry.contains(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
        return when (requirement) {
            is PackAuthRequirement.SmartyCraft ->
                if (scSatisfiable) prepareScAuth(requirement.serverId, currentSession, instance) else currentSession
            is PackAuthRequirement.Both ->
                if (scSatisfiable) prepareScAuth(requirement.serverId, currentSession, instance) else currentSession
            PackAuthRequirement.Microsoft ->
                if (!authProviderRegistry.contains(PackAuthRequirement.Microsoft.PROVIDER_KEY)) {
                    currentSession.toOffline()
                } else {
                    credentialsManager.accountFor(PackAuthRequirement.Microsoft.PROVIDER_KEY)
                        ?: run {
                            ActionRing.record(
                                "Pack launch ${instance.displayName}: Microsoft account required, none signed in",
                            )
                            fail(LaunchError.MissingAuthProvider(PackAuthRequirement.Microsoft.PROVIDER_KEY))
                            null
                        }
                }
        }
    }

    /**
     * SmartyCraft pre-spawn re-auth for an SC-bound pack. Returns the refreshed
     * [SessionData], a 2FA-fallback session with the cached manifest attached, or
     * null after [fail] has already set the error state -- the caller bails on
     * null.
     *
     * Precondition: missing player + password fails with
     * [LaunchError.MissingAuthProvider] rather than spawning the game and waiting
     * for the SC join to reject the stale token; the surface is friendlier and the
     * diagnosis is unambiguous.
     */
    private suspend fun prepareScAuth(
        serverId: String,
        currentSession: SessionData,
        instance: PackInstance,
    ): SessionData? {
        // Multi-active: an SC-bound pack always uses the SmartyCraft account,
        // regardless of which account is the chrome "primary".
        // Experimental: go with the token already in hand rather than minting a
        // fresh one. The saved session lasts at least a day, so re-authenticating
        // per launch is what makes a two-factor account ask for a code every time.
        // With this on it asks once, at sign-in, and a stale token surfaces as a
        // join refusal the player can act on -- not a code prompt before a game
        // that would have run. The server, not a timer, decides when it is spent.
        if (settingsService.getSettings().experimentalReuseSession && currentSession.reusableForSc()) {
            emit(LaunchLogEvent.AuthSucceeded(currentSession.uuid))
            ActionRing.record("Pack launch ${instance.displayName}: reusing the session in hand (experimental)")
            return if (currentSession.serverId == serverId) currentSession
            else currentSession.copy(serverId = serverId)
        }

        val saved = credentialsManager.accountFor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
        val pass = saved?.cachedPassword ?: currentSession.cachedPassword
        val playerName = currentSession.playerName.ifBlank { saved?.playerName ?: "" }
        if (playerName.isBlank() || pass.isNullOrEmpty()) {
            ActionRing.record(
                "Pack launch ${instance.displayName}: missing SC credentials for '$serverId'",
            )
            fail(LaunchError.MissingAuthProvider(PackAuthRequirement.SmartyCraft.PROVIDER_KEY))
            return null
        }
        if (currentSession.twoFactor && !currentSession.mintedNow) {
            // A second-factor account gets a session minted for THIS launch. Carrying
            // the stored one forward is cheaper but not verifiable: any login from
            // anywhere -- a second pack, another machine -- has since invalidated it,
            // and the player would find out only when the server refuses the join.
            // One code per launch buys a token that is known good at spawn time.
            // The UI answers this by prompting and relaunching with the fresh session.
            fail(LaunchError.TwoFactorExpired)
            return null
        }
        return try {
            val fresh = authService.login(playerName, pass, serverId)
            emit(LaunchLogEvent.AuthSucceeded(fresh.uuid))
            fresh
        } catch (_: TwoFactorRequiredException) {
            // First contact with the gate, before the account is flagged. Carrying the
            // stored session forward here was the old plan and it is the failure the
            // prompt exists to prevent: the launch would go on with a token nothing
            // minted for it. Stop and let the gate ask for a code, same as the flagged
            // path above.
            emit(LaunchLogEvent.TwoFactorDetected)
            ActionRing.record("Pack launch ${instance.displayName}: second factor required for '$serverId'")
            fail(LaunchError.TwoFactorExpired)
            null
        } catch (e: Exception) {
            // A refresh that did not go through means this launch has no session it
            // earned, so it gets none: the pack starts offline with the token
            // stripped rather than carrying the old one into the game process. That
            // covers the flaky-connection case as well -- a launch that could not
            // reach the auth server IS an offline launch, and saying so up front
            // beats a client that looks online until the server refuses the join.
            emit(LaunchLogEvent.AuthFailed(e.message, classifyAuthFailure(e)))
            emit(LaunchLogEvent.OfflineSkipAuth)
            currentSession.toOffline()
        }
    }

    /**
     * Whether this session can be carried into an SC-bound launch as-is: a
     * SmartyCraft session with a token, not an offline one and not Microsoft.
     *
     * Deliberately NOT keyed on `status == OK`. A session restored from the store
     * after a restart carries `status = null` (see CredentialsManager.loadSession),
     * so requiring OK let the reuse work in the same run yet fail the very next
     * launch, which is when it matters most: the account is signed in without a
     * code, then a pack launch demands one anyway. A non-blank token is the real
     * signal there is something to reuse; `refreshToken == null` keeps a Microsoft
     * session (its only carrier of a refresh token) out of the SC path.
     */
    private fun SessionData.reusableForSc(): Boolean =
        !offline && refreshToken == null && accessToken.isNotBlank() && playerName.isNotBlank()

    /**
     * The same session with nothing on it that could join a server: vanilla offline
     * uuid, no token, marked offline (which is what puts `--userType legacy` on the
     * command line). Minting the offline uuid from the player name rather than
     * keeping the online one is what makes singleplayer worlds line up with other
     * launchers' offline mode.
     */
    private fun SessionData.toOffline(): SessionData = copy(
        uuid = if (offline) uuid else OfflineIdentity.dashlessUuidFor(playerName),
        accessToken = "",
        offline = true,
    )

    /**
     * Maps a failed pre-spawn refresh onto the distinction the UI acts on.
     *
     * A network-shaped failure never reached the auth server, so the token in
     * hand is as good (or as stale) as it was before the attempt. Anything the
     * server answered with -- bad credentials, dead session, locked account --
     * is a verdict on those credentials, and the game's join will get the same
     * one. INTERNAL_ERROR is deliberately NOT a rejection: the auth layer uses
     * it as its catch-all for failures it could not attribute, and calling
     * those "the server refused you" would send the user to re-enter a password
     * that was never the problem.
     */
    private fun classifyAuthFailure(e: Exception): AuthRefreshFailure = when {
        e !is AuthException -> AuthRefreshFailure.Unknown
        e.isNetworkError || e.isSslError -> AuthRefreshFailure.Unreachable
        e.status == AuthStatus.INTERNAL_ERROR -> AuthRefreshFailure.Unknown
        else -> AuthRefreshFailure.Rejected
    }

    /**
     * Stops the in-flight launch.
     *
     * With a game up, it asks the game to end and leaves the rest to the launch:
     * the state reads [LaunchState.Stopping] until the process has actually gone,
     * and the launch's own tail then records the session and settles to Idle. It
     * used to set Idle at once and cancel the launch, which reopened Play while the
     * old game had up to the termination grace left, and skipped the tail that is
     * the only writer of playtime.
     *
     * Still preparing, there is no game to wait for: the launch is cancelled and
     * the gate reopens now.
     */
    fun abort() {
        synchronized(launchLock) {
            currentAbortToken?.set(true)
            val handle = runningHandle
            if (handle != null) {
                _state.value = LaunchState.Stopping(handle)
                runCatching { handle.terminate() }
            } else {
                launchJob?.cancel()
                _state.value = LaunchState.Idle
            }
        }
    }

    /**
     * Stops the launch of [instanceId], and nothing else.
     *
     * What a pack's own control calls. A control left showing Stop after its
     * launch ended would otherwise end whichever game is running now, which is not
     * the one it names.
     */
    fun abort(instanceId: String) {
        if (_runningPackInstanceId.value == instanceId) abort()
    }

    /**
     * Records the running game's session up to now, for a launcher that is about to
     * quit and leave the game running. Its launch never reaches its own tail in that
     * case, so this is the only record the session gets. Minutes counted here are
     * not counted again if the game does exit first.
     */
    suspend fun settleSessionForQuit() {
        val live = liveSession ?: return
        val record = live.record ?: return
        runCatching { record(live.take()) }.onFailure { logger.warn("Recording the session before quit failed", it) }
    }

    private fun setStage(stage: PrepareStage, progress: Float) {
        _state.value = LaunchState.Prepare(stage, progress)
    }
}
