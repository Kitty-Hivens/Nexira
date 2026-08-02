package hivens.launcher.launch

import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.api.interfaces.*
import hivens.core.api.model.ServerProfile
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
import hivens.core.diag.ActionRing
import hivens.core.io.InstanceMutationLock
import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import hivens.core.launch.SpawnResult
import hivens.launcher.di.AppCoroutineScopeHook
import hivens.launcher.platform.ServerNameValidator
import hivens.launcher.smrt.ClientSyncCoordinator
import hivens.launcher.smrt.OpenSmrtHelperResolver
import hivens.launcher.smrt.SmartyModPlanner
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.slf4j.MDCContext

/**
 * Constructor injection (not `KoinComponent` + `by inject()`) so the
 * controller is testable without bootstrapping Koin. `singleOf(::LauncherController)`
 * in [hivens.launcher.di.launchPipelineModule] resolves every parameter from the
 * graph automatically; production wiring stays a one-liner.
 *
 * Note: [appScope] is the shared `single<CoroutineScope>(createdAtStart)`
 * registered alongside [AppCoroutineScopeHook] -- the
 * JVM shutdown hook cancels every in-flight launch on process exit. The
 * prior dedicated `CoroutineScope(SupervisorJob() + IO)` here was
 * unreachable from any shutdown hook, so a SIGTERM mid-launch could
 * leave the spawned game process and its sockets hanging.
 */
class LauncherController(
    private val authService: AuthProvider,
    private val authProviderRegistry: AuthProviderRegistry,
    private val credentialsManager: ICredentialStore,
    private val settingsService: ISettingsService,
    private val downloadService: IFileDownloadService,
    private val javaManagerService: IJavaManager,
    private val launcherService: ILauncherService,
    private val manifestProcessor: IManifestProcessorService,
    private val manifestCache: IManifestStore,
    private val profileManager: IInstanceProfileStore,
    private val packRepository: IPackRepository,
    private val smrtPackClient: IMirrorPackClient,
    private val smrtSyncService: IPackSyncService,
    private val smartyPlanner: SmartyModPlanner,
    private val dataDirectory: Path,
    private val appScope: CoroutineScope,
) {

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
        val updated = instance.copy(optionalContent = toggles)
        packRepository.put(updated)
        val clientDir = dataDirectory.resolve("instances").resolve(instance.instanceDirName)
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
     */
    fun setOptionalModsAsync(
        instance: PackInstance,
        manifest: SmrtPackManifest,
        toggles: List<ContentToggle>,
    ) {
        appScope.launch { setOptionalMods(instance, manifest, toggles) }
    }

    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

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
     * Per-launch abort token. Each launch installs a fresh
     * AtomicBoolean here and captures it in its coroutine; [abort] flips
     * whatever token is current. The launch coroutine -- parked in the
     * blocking `process.waitFor()`, which resumes with SIGTERM code 143
     * once the process is destroyed -- checks ITS OWN captured token to
     * tell a user stop from a crash.
     *
     * A single shared flag would race: aborting game A then immediately
     * launching B (allowed, because abort sets state Idle synchronously
     * while A's coroutine is still blocked in waitFor) would let B reset
     * the flag before A's exit handler reads it, so A would falsely
     * report a crash and clobber B's state. A per-launch token each
     * coroutine reads in isolation removes the race.
     */
    @Volatile private var currentAbortToken: AtomicBoolean? = null

    /**
     * SC server-list launch. Delegates the gate/token/MDC/spawn/wait/exit
     * machinery to [launchInternal]; [prepareServerLaunch] supplies the
     * SC-specific auth + sync + java steps and the spawn binding.
     */
    fun launch(
        currentSession: SessionData,
        server: ServerProfile,
        onSessionRefreshed: ((SessionData) -> Unit)? = null,
    ) = launchInternal(
        label = server.name,
        onStart = {
            emit(LaunchLogEvent.SessionStarted(server.assetDir, server.name))
            emit(LaunchLogEvent.AppBanner)
            emit(LaunchLogEvent.TargetServer(server.name, settingsService.getSettings().isOfflineMode))
        },
        prepare = { prepareServerLaunch(currentSession, server, onSessionRefreshed) },
    )

    /**
     * Outcome of a prepare phase. [Ready] carries the path-specific spawn (and
     * an optional post-spawn hook); [Bail] means prepare already called [fail]
     * and the flow must stop WITHOUT overwriting that error state.
     */
    private sealed interface Prepared {
        class Ready(
            val spawn: suspend (onLog: (String, LauncherLogType) -> Unit) -> SpawnResult,
            /** Runs once after the process spawns. [launchInternal] guards it, so it never fails the launch. */
            val onSpawned: (suspend () -> Unit)? = null,
            /** Runs once after the game process exits, with the session length in seconds. Guarded like [onSpawned]. */
            val onExit: (suspend (sessionSeconds: Long) -> Unit)? = null,
        ) : Prepared

        data object Bail : Prepared
    }

    /**
     * Owns the launch state machine shared by both entry points: the atomic
     * re-entry gate, the per-launch abort token + MDC tag, the spawn, the
     * blocking wait, the exit-code verdict, and the cancellation-vs-crash catch
     * tail. [prepare] runs the path-specific steps and returns a
     * [Prepared.Ready] to spawn or [Prepared.Bail] to stop.
     */
    private fun launchInternal(
        label: String,
        onStart: () -> Unit,
        prepare: suspend CoroutineScope.() -> Prepared,
    ) {
        // Re-entry guard must be atomic with the launchJob assignment. Without
        // the lock two parallel callers (UI double-click, tray-launch racing
        // dashboard-launch) could both observe Idle, both pass the gate, both
        // assign launchJob, and produce two in-flight game spawns -- of which
        // only the second is tracked for abort(). Claim the state slot under
        // the lock; the coroutine runs outside it so the gate isn't held
        // during the long flow.
        synchronized(launchLock) {
            if (_state.value !is LaunchState.Idle &&
                _state.value !is LaunchState.Error) return
            _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
        }

        // Tag every log line for this attempt with a stable launchId so a user
        // dump can be sliced per-play-click (`grep launchId=abcd1234 *.log`).
        // MDCContext (from kotlinx-coroutines-slf4j) propagates it across every
        // dispatcher hop the flow takes, including FileDownloadService and
        // LauncherService.
        val launchId = UUID.randomUUID().toString().take(8)
        val abortToken = AtomicBoolean(false)
        currentAbortToken = abortToken

        launchJob = appScope.launch(MDCContext(mapOf("launchId" to launchId))) {
            try {
                _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
                onStart()
                ActionRing.record("Launching: $label (launchId=$launchId)")

                val prepared = when (val r = prepare()) {
                    // prepare() already called fail(); stop without touching _state.
                    is Prepared.Bail -> return@launch
                    is Prepared.Ready -> r
                }

                setStage(PrepareStage.LAUNCH, 0.95f)
                ActionRing.record("Game running: $label")
                emit(LaunchLogEvent.Launching)

                when (val result = prepared.spawn { text, type -> emit(LaunchLogEvent.ProcessOutput(text, type)) }) {
                    // The service maps its own failures (provisioning, spawn IO,
                    // SC-binding block) to a semantic LaunchError; surface it.
                    is SpawnResult.Failed -> fail(result.error)
                    is SpawnResult.Started -> {
                        val handle = result.handle
                        runningHandle = handle
                        _state.value = LaunchState.GameRunning(handle)
                        // Post-spawn hook guarded centrally: a throwing hook must
                        // not flip the running game into an Error state.
                        prepared.onSpawned?.let { hook ->
                            runCatching { hook() }.onFailure { logger.warn("Post-spawn hook failed for {}", label, it) }
                        }
                        val sessionStart = Instant.now().epochSecond

                        // Reads its OWN captured abortToken, never the
                        // currentAbortToken field -- see that field's KDoc for the
                        // abort-A-then-launch-B race a shared flag would reopen.
                        val exitCode = handle.awaitExit()
                        runningHandle = null
                        ActionRing.record("Game exited: $label (code $exitCode)")
                        prepared.onExit?.let { hook ->
                            val secs = (Instant.now().epochSecond - sessionStart).coerceAtLeast(0)
                            runCatching { hook(secs) }.onFailure { logger.warn("Post-exit hook failed for {}", label, it) }
                        }

                        if (exitCode != 0 && !abortToken.get()) {
                            fail(LaunchError.ExitCode(exitCode))
                        } else {
                            _state.value = LaunchState.Idle
                        }
                    }
                }
            } catch (e: Exception) {
                runningHandle = null
                if (e !is CancellationException) {
                    logger.error("Launch flow failed for {}", label, e)
                    fail(LaunchError.Internal(e.message ?: ""), e)
                } else {
                    _state.value = LaunchState.Idle
                }
            }
        }
    }

    /**
     * SC server-list prepare phase: auth (skipped offline), ignored-file
     * calculation, sync (or offline manifest recovery), and Java resolution.
     * Bails -- with the semantic [LaunchError] already set on [fail] -- for the
     * 2FA-no-manifest, offline-no-client/manifest, and helper-unavailable cases.
     *
     * A [CoroutineScope] extension so `isActive` inside the sync callbacks reads
     * the launch coroutine's cancellation, matching the pre-extraction body.
     */
    private suspend fun CoroutineScope.prepareServerLaunch(
        currentSession: SessionData,
        server: ServerProfile,
        onSessionRefreshed: ((SessionData) -> Unit)?,
    ): Prepared {
        val settings = settingsService.getSettings()
        val isOffline = settings.isOfflineMode

        // 1. Auth -- skip in offline mode
        setStage(PrepareStage.AUTH, 0.1f)
        var session = currentSession
        val targetServerId = server.assetDir

        if (isOffline) {
            emit(LaunchLogEvent.OfflineSkipAuth)
            // Offline: no SC auth, so the bound server cannot be joined -- the
            // client still launches for singleplayer/LAN. Mint a proper offline
            // identity (vanilla OfflinePlayer UUID, blank token -> "0" in argv +
            // userType legacy) rather than carrying a stale/garbage session.
            ActionRing.record(
                "Offline launch of '$targetServerId': singleplayer only, the server cannot be joined without auth",
            )
            session = session.copy(
                uuid = if (session.offline) session.uuid else OfflineIdentity.dashlessUuidFor(session.playerName),
                accessToken = "",
                offline = true,
            )
        } else {
            try {
                val pass = credentialsManager.accountFor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)?.cachedPassword
                    ?: session.cachedPassword
                if (session.twoFactor && !session.mintedNow) {
                    // Same as the pack path: mint the session for this launch rather
                    // than trust a stored one nothing can vouch for.
                    fail(LaunchError.TwoFactorExpired)
                    return Prepared.Bail
                } else if (!pass.isNullOrEmpty()) {
                    session = authService.login(session.playerName, pass, targetServerId)
                    onSessionRefreshed?.invoke(session)
                    emit(LaunchLogEvent.AuthSucceeded(session.uuid))
                } else {
                    emit(LaunchLogEvent.NoPassword)
                }
            } catch (_: TwoFactorRequiredException) {
                // The demand itself says the account is two-factor; the UI persists
                // that so later launches stop logging in behind the user's back.
                //
                // And it stops here rather than continuing on the stored session: a
                // cached manifest would let the sync run, but the game would still be
                // handed a token nothing minted for this launch, which is exactly what
                // the code prompt exists to prevent.
                emit(LaunchLogEvent.TwoFactorDetected)
                ActionRing.record("Launch: second factor required for $targetServerId")
                fail(LaunchError.TwoFactorExpired)
                return Prepared.Bail
            } catch (e: Exception) {
                // No fresh session, so no session at all: same rule as the pack
                // path. Carrying the previous token forward is what produced the
                // launch that looks fine until the server answers "Failed to verify
                // username" with nothing pointing back here.
                emit(LaunchLogEvent.AuthFailed(e.message, classifyAuthFailure(e)))
                emit(LaunchLogEvent.OfflineSkipAuth)
                session = session.toOffline()
            }
        }

        // 2. Ignored files
        val ignoredFiles = calculateIgnoredFiles(server)

        // 3. Download -- skip in offline mode if client exists
        setStage(PrepareStage.SYNC, 0.2f)
        val clientDir = dataDirectory.resolve("clients").resolve(ServerNameValidator.require(targetServerId))
        if (!Files.exists(clientDir)) Files.createDirectories(clientDir)

        if (isOffline) {
            // In offline mode, skip file sync but verify client exists.
            // .use{} closes the directory stream; without it the OS file handle
            // leaks until GC eventually collects the stream.
            val hasClient = Files.exists(clientDir) &&
                Files.list(clientDir).use { it.count() > 0 }
            if (!hasClient) {
                fail(LaunchError.OfflineNoClient)
                return Prepared.Bail
            }
            // Recover the file manifest from the last successful online sync.
            // Without it, ClasspathProvider has nothing to walk and builds an
            // empty -cp argument -- the JVM then dies with "Could not find or
            // load main class net.minecraft.launchwrapper.Launch" because the
            // class IS on disk but classpath is "". TTL is intentionally ignored
            // here: a stale-but-present manifest is strictly better than
            // launching with no classpath. If the user has never logged in
            // online, the cache is empty, and we bail with an actionable error
            // rather than a cryptic JVM message.
            if (session.fileManifest == null) {
                val cached = manifestCache.loadManifest(targetServerId)
                if (cached != null) {
                    session = session.copy(fileManifest = cached)
                } else {
                    fail(LaunchError.OfflineNoManifest)
                    return Prepared.Bail
                }
            }
            emit(LaunchLogEvent.OfflineSkipSync)
        } else {
            // Smarty swap / strict plan -- computed here (not in the offline
            // branch) so an offline launch never makes the resolver's doomed
            // network fetch.
            val smartyPlan = smartyPlanner.plan(server, session.fileManifest, settings)

            // Block rather than strip Smarty with no replacement: if the swap is
            // on and the manifest ships Smarty but no helper is available for
            // this MC version (unsupported version / descriptor down / nothing
            // cached), launching would either join with no network mod (kick)
            // or, if we kept Smarty, run the surveillance mod.
            if (settings.useOpenSmrtHelper && smartyPlan.ignoredAddon.isNotEmpty() &&
                !helperPresent(clientDir, server.version, smartyPlan)) {
                fail(LaunchError.HelperUnavailable(server.version))
                return Prepared.Bail
            }

            ClientSyncCoordinator.withClientLock(clientDir) {
                downloadService.processSession(
                    session = session,
                    serverId = targetServerId,
                    targetDir = clientDir,
                    extraCheckSum = server.extraCheckSum,
                    ignoredFiles = ignoredFiles + smartyPlan.ignoredAddon,
                    messageUI = { /* log */ },
                    progressUI = { progress ->
                        if (!isActive) return@processSession
                        _state.value = LaunchState.Downloading(
                            currentFileIdx   = progress.currentFileIdx,
                            totalFiles       = progress.totalFiles,
                            downloadedBytes  = progress.downloadedBytes,
                            totalBytes       = progress.totalBytes,
                            speedBytesPerSec = progress.bytesPerSec,
                        )
                    },
                    // Map integrity-walk progress onto the SYNC stage's 0.2..0.7
                    // sub-range. The actual download progress takes over from
                    // 0.7 upward via the Downloading state above. Without this,
                    // the progress bar froze at 20% during the MD5 walk on
                    // 1000-file modpacks -- 5-30s of perceived hang.
                    verifyUI = { verified, total ->
                        if (!isActive) return@processSession
                        val fraction = if (total > 0) verified.toFloat() / total else 0f
                        setStage(PrepareStage.SYNC, 0.2f + 0.5f * fraction)
                    },
                    injectModJar = smartyPlan.injectJar,
                    strictModCheck = smartyPlan.strict,
                    helperKeepGlobs = smartyPlan.helperKeepGlobs,
                )
            }
        }

        // 4. Java
        setStage(PrepareStage.JVM, 0.9f)
        val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
            Path.of(settings.javaPath!!)
        } else {
            javaManagerService.getJavaPath(server.version)
        }

        // 5. Spawn binding handed back to launchInternal.
        return Prepared.Ready(
            spawn = { onLog ->
                launcherService.launchClientWithLogs(
                    sessionData = session,
                    serverProfile = server,
                    clientRootPath = clientDir,
                    javaExecutablePath = javaPath,
                    allocatedMemoryMB = settings.memoryMB,
                    adaptiveEnabled = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled,
                    onLog = onLog,
                )
            },
        )
    }

    /**
     * Pack-centric launch path for the Hivens mirror world: a [PackInstance]
     * from the local Library. Re-entry guard, MDC tagging, and abort semantics
     * come from [launchInternal]; [preparePackLaunch] supplies the manifest
     * resolve + pack auth + spawn binding, so the existing UI surfaces
     * (LaunchControlPanel, GameConsoleService) plug in unchanged.
     */
    fun launchPackInstance(
        currentSession: SessionData,
        packInstance: PackInstance,
    ) = launchInternal(
        label = packInstance.displayName,
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
     * Pack-centric prepare phase. Skips SC auth + per-launch asset re-sync
     * (mirror packs are static + already on disk after install):
     * - Resolves the [CachedManifestSnapshot]; when [PackInstance.cachedManifest]
     *   is null (instance predates the field) a one-time mirror fetch fills it
     *   and writes it back via [IPackRepository.put].
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

        // 3. Auth requirement: refresh the session right before spawn. Mirrors
        // the SC server path's pre-spawn re-auth.
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
        val authRequirement = PackAuthRouter.requirementFor(refreshedInstance, manifestSnapshot.authRequirement)

        // 2b. Hold the instance to the pack -- but only where a token is at stake.
        // A server-bound pack is the case that matters: we are about to hand the
        // game a session that logs into someone's server, and a jar the pack never
        // named is what that session would be lent to. A pack with no binding has no
        // server and gets no token, so what its owner puts in mods/ is their game and
        // none of our business.
        //
        // Held against the roster written to the instance at sync time, so it answers
        // with no network and an offline launch is covered too.
        // The manifest's OWN declaration, not the router's answer: the router falls
        // back to Microsoft for any mirror pack, so it is true of a solo pack as well
        // and would put every instance under the strict rule again.
        val serverBound = manifestSnapshot.authRequirement != null
        val verdict = if (serverBound) {
            smrtSyncService.enforceRoster(clientDir)
        } else {
            RosterVerdict(verified = true)
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
        } else if (authRequirement != null) {
            setStage(PrepareStage.AUTH, 0.4f)
            session = preparePackAuth(authRequirement, currentSession, refreshedInstance)
                ?: return Prepared.Bail
        }

        // 4. Java override. The pack launch path picks the LOADER-declared Java
        // itself (resolved.javaMajor) from the resolved runtime -- same MC +
        // different loader can need different Java (Cleanroom-1.12.2 -> 25 vs
        // legacy-Forge-1.12.2 -> 8), so the version-keyed heuristic moves out of
        // the controller. We only pass the user's explicit global setting; null
        // means "let the service provision."
        setStage(PrepareStage.JVM, 0.7f)
        val javaOverride: Path? = settings.javaPath
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }

        // 5. Spawn binding handed back to launchInternal.
        return Prepared.Ready(
            spawn = { onLog ->
                launcherService.launchPackClient(
                    sessionData          = session,
                    // Carry the EFFECTIVE requirement (manifest value or the
                    // router's origin-derived one) so the service's SC-binding step
                    // sees it; the raw snapshot's authRequirement is null for packs
                    // whose mirror manifest has no auth block yet (e.g. Industrial).
                    manifest             = manifestSnapshot.copy(authRequirement = authRequirement),
                    runtime              = refreshedInstance.runtime,
                    clientRootPath       = clientDir,
                    javaPathOverride     = javaOverride,
                    allocatedMemoryMB    = settings.memoryMB,
                    adaptiveEnabled      = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled,
                    // Redirect authlib away from the Mojang hosts only when the
                    // session being carried is an SC one. Keying this on the
                    // pack's ORIGIN instead put a mirror pack with no auth block
                    // behind the redirect while PackAuthRouter had already
                    // resolved it to Microsoft -- the launch would hand a
                    // Microsoft token to the SC host. Same test the service uses
                    // for its SC binding, so the two cannot disagree.
                    redirectAuthHost     = authRequirement?.scServerId != null,
                    // Same partition the roster sweep uses, and for the same
                    // reason: a bound launch is handed a token, so the loader
                    // hooks it inherits are a way to run code beside it. Taken
                    // from the manifest's own declaration rather than the
                    // effective requirement -- the router answers Microsoft for
                    // every mirror pack, which would seal a solo pack too and
                    // cost its owner MangoHud for nothing.
                    sealEnvironment      = serverBound,
                    // Auth mechanism for an SC-bound join: the redirect agent
                    // (default on) and/or SC's patched authlib jar (default off,
                    // fallback). Both no-op on non-SC packs.
                    useNetworkAgent       = settings.useNetworkAgent,
                    useSmartycraftAuthLib = settings.useSmartycraftAuthLib,
                    displayName          = refreshedInstance.displayName,
                    onLog                = onLog,
                )
            },
            onSpawned = {
                packRepository.put(
                    refreshedInstance.copy(lastPlayedEpochOrZero = Instant.now().epochSecond),
                )
            },
            onExit = { secs ->
                // Re-read the persisted instance (onSpawned wrote lastPlayed; the
                // user may have edited it mid-session) and add the session onto
                // THAT, so neither write clobbers the other. Skip when it's gone --
                // never resurrect an instance deleted while it ran.
                packRepository.get(refreshedInstance.id)?.let { current ->
                    packRepository.put(current.copy(playtimeSeconds = current.playtimeSeconds + secs))
                }
            },
        )
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
        runCatching { packRepository.put(refreshed) }
            .onFailure { logger.warn("Failed to persist cachedManifest for ${instance.id}", it) }
        return snapshot to refreshed
    }

    /**
     * Pack-side pre-spawn auth, dispatched by the pack's [PackAuthRequirement].
     * A requirement is enforced only for a provider the [authProviderRegistry] can
     * satisfy: SC-bound requirements ([PackAuthRequirement.SmartyCraft], and the SC
     * half of [PackAuthRequirement.Both]) re-auth via [prepareScAuth] when SC is
     * registered; [PackAuthRequirement.Microsoft] -- and any SC requirement whose
     * provider is somehow absent -- is advisory, so the pack launches with the
     * current session. A newly registered provider activates its gate on its own.
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
                    currentSession // no Microsoft provider configured -> advisory (Phase A behavior)
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
     * SmartyCraft pre-spawn re-auth for an SC-bound pack, mirroring the SC
     * server-list path's pre-spawn re-auth (see [launch], around the AUTH stage).
     * Returns the refreshed [SessionData], a 2FA-fallback session with the cached
     * manifest attached, or null after [fail] has already set the error state -- the
     * caller bails on null.
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
     * Stops the in-flight launch. If the game process has already spawned,
     * terminates it via [LaunchHandle.terminate] before resetting state --
     * canceling the coroutine alone would orphan the spawned process and the
     * next [launch] click would happily spawn a second game.
     */
    fun abort() {
        currentAbortToken?.set(true)
        val handle = runningHandle
        runningHandle = null
        runCatching { handle?.terminate() }
        launchJob?.cancel()
        _state.value = LaunchState.Idle
    }

    private fun setStage(stage: PrepareStage, progress: Float) {
        _state.value = LaunchState.Prepare(stage, progress)
    }

    private fun calculateIgnoredFiles(server: ServerProfile): Set<String> {
        val userState = profileManager.getProfile(server.assetDir).optionalModsState
        return manifestProcessor.calculateIgnoredFiles(server, userState)
    }

    /**
     * A helper is usable for [mcVersion] when one was resolved this launch
     * ([SmartyModPlanner.Plan.injectJar]) or a previously-injected one of the
     * exact expected name is still on disk.
     */
    private fun helperPresent(clientDir: Path, mcVersion: String, plan: SmartyModPlanner.Plan): Boolean =
        plan.injectJar != null ||
            Files.isRegularFile(clientDir.resolve("mods").resolve(OpenSmrtHelperResolver.helperFileName(mcVersion)))

}
