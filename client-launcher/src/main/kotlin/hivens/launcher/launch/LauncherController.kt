package hivens.launcher.launch

import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.*
import hivens.core.api.model.ServerProfile
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import hivens.launcher.CredentialsManager
import hivens.launcher.ManifestCache
import hivens.launcher.ProfileManager
import hivens.launcher.di.AppCoroutineScopeHook
import hivens.launcher.smrt.SmrtPackClient
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
import kotlinx.coroutines.slf4j.MDCContext

/**
 * Constructor injection (not `KoinComponent` + `by inject()`) so the
 * controller is testable without bootstrapping Koin. `singleOf(::LauncherController)`
 * in [hivens.launcher.di.appModule] resolves every parameter from the
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
    private val authService: IAuthService,
    private val credentialsManager: CredentialsManager,
    private val settingsService: ISettingsService,
    private val downloadService: IFileDownloadService,
    private val javaManagerService: IJavaManager,
    private val launcherService: ILauncherService,
    private val manifestProcessor: IManifestProcessorService,
    private val manifestCache: ManifestCache,
    private val profileManager: ProfileManager,
    private val packRepository: IPackRepository,
    private val smrtPackClient: SmrtPackClient,
    private val dataDirectory: Path,
    private val appScope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(LauncherController::class.java)

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
        emit(LaunchLogEvent.RequestConsoleVisible)
    }

    private var launchJob: Job? = null
    /**
     * Tracked separately from [launchJob] so [abort] can kill the live
     * Process even after the coroutine completes the spawn step. Cleared
     * after `process.waitFor()` returns so subsequent abort() calls don't
     * try to destroy an already-finished process. Volatile so the abort
     * thread sees the latest write done from the launch coroutine.
     */
    @Volatile private var runningProcess: Process? = null
    private val launchLock = Any()

    fun launch(
        currentSession: SessionData,
        server: ServerProfile,
        onSessionRefreshed: ((SessionData) -> Unit)? = null
    ) {
        // Re-entry guard must be atomic with the launchJob assignment.
        // Without the lock two parallel callers (UI double-click,
        // tray-launch racing dashboard-launch) could both observe Idle, both
        // pass the gate, both assign launchJob, and produce two in-flight
        // game spawns -- of which only the second is tracked for abort().
        // Claim the state slot under the lock; the coroutine still runs
        // outside the lock so the gate isn't held during the long flow.
        synchronized(launchLock) {
            if (_state.value !is LaunchState.Idle &&
                _state.value !is LaunchState.Error) return
            _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
        }

        // Tag every log line emitted during this launch attempt with a stable
        // launchId so a user dump can be sliced per-play-click via
        // `grep launchId=abcd1234 *.log`. MDCContext (from
        // kotlinx-coroutines-slf4j) propagates the value across every
        // dispatcher hop the launch flow takes, including the downstream
        // FileDownloadService coroutines and LauncherService.
        val launchId = UUID.randomUUID().toString().take(8)

        launchJob = appScope.launch(MDCContext(mapOf("launchId" to launchId))) {
            val settings = settingsService.getSettings()
            val isOffline = settings.isOfflineMode

            try {
                _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)

                emit(LaunchLogEvent.SessionStarted)
                emit(LaunchLogEvent.AppBanner)
                emit(LaunchLogEvent.TargetServer(server.name, isOffline))

                ActionRing.record("Launching: ${server.name} (launchId=$launchId)")

                // 1. Auth -- skip in offline mode
                setStage(PrepareStage.AUTH, 0.1f)
                var session = currentSession
                val targetServerId = server.assetDir

                if (isOffline) {
                    emit(LaunchLogEvent.OfflineSkipAuth)
                    // In offline mode, use whatever session we have (or a stub)
                    if (session.accessToken.isBlank()) {
                        session = session.copy(accessToken = "offline")
                    }
                } else {
                    try {
                        val pass = credentialsManager.load()?.cachedPassword ?: session.cachedPassword
                        if (!pass.isNullOrEmpty()) {
                            session = authService.login(session.playerName, pass, targetServerId)
                            onSessionRefreshed?.invoke(session)
                            emit(LaunchLogEvent.AuthSucceeded(session.uuid))
                        } else {
                            emit(LaunchLogEvent.NoPassword)
                        }
                    } catch (_: TwoFactorRequiredException) {
                        // 2FA account -- refusing to prompt the user for a code
                        // every time they click Play. The cached accessToken
                        // in `session` is from a previous successful 2FA flow
                        // and is what the game uses anyway. Augment it with a
                        // cached manifest (same path the offline branch
                        // takes) so processSession has something to walk.
                        val cached = manifestCache.loadManifest(targetServerId)
                        if (cached != null) {
                            session = session.copy(fileManifest = cached)
                            ActionRing.record("Launch: 2FA account, using cached manifest for $targetServerId")
                        } else {
                            // No cached manifest and no fresh login --
                            // bail with the semantic TwoFactorExpired
                            // reason so the UI renders an actionable
                            // "re-login from the form" message instead
                            // of a generic internal-error path.
                            ActionRing.record("Launch: 2FA + no cached manifest for $targetServerId -- re-login required")
                            fail(LaunchError.TwoFactorExpired)
                            return@launch
                        }
                    } catch (e: Exception) {
                        // Non-2FA auth failure: log and continue with the
                        // existing (possibly stale) session -- graceful
                        // degradation, the game itself will reject if the
                        // token has truly expired.
                        emit(LaunchLogEvent.AuthFailed(e.message))
                    }
                }

                // 2. Ignored files
                val ignoredFiles = calculateIgnoredFiles(server)

                // 3. Download -- skip in offline mode if client exists
                setStage(PrepareStage.SYNC, 0.2f)
                val clientDir = dataDirectory.resolve("clients").resolve(targetServerId)
                if (!Files.exists(clientDir)) Files.createDirectories(clientDir)

                if (isOffline) {
                    // In offline mode, skip file sync but verify client exists.
                    // .use{} closes the directory stream; without it the OS
                    // file handle leaks until GC eventually collects the stream.
                    val hasClient = Files.exists(clientDir) &&
                        Files.list(clientDir).use { it.count() > 0 }
                    if (!hasClient) {
                        fail(LaunchError.OfflineNoClient)
                        return@launch
                    }
                    // Recover the file manifest from the last successful online sync.
                    // Without it, ClasspathProvider has nothing to walk and builds an
                    // empty -cp argument -- the JVM then dies with "Could not find or
                    // load main class net.minecraft.launchwrapper.Launch" because the
                    // class IS on disk but classpath is "". TTL is intentionally
                    // ignored here: a stale-but-present manifest is strictly better
                    // than launching with no classpath. If the user has never logged
                    // in online, the cache is empty, and we bail with an actionable
                    // error rather than a cryptic JVM message.
                    if (session.fileManifest == null) {
                        val cached = manifestCache.loadManifest(targetServerId)
                        if (cached != null) {
                            session = session.copy(fileManifest = cached)
                        } else {
                            fail(LaunchError.OfflineNoManifest)
                            return@launch
                        }
                    }
                    emit(LaunchLogEvent.OfflineSkipSync)
                } else {
                    downloadService.processSession(
                        session = session,
                        serverId = targetServerId,
                        targetDir = clientDir,
                        extraCheckSum = server.extraCheckSum,
                        ignoredFiles = ignoredFiles,
                        messageUI = { /* log */ },
                        progressUI = { current, total, bytesRead, totalBytes, speed ->
                            if (!isActive) return@processSession
                            _state.value = LaunchState.Downloading(
                                currentFileIdx   = current,
                                totalFiles       = total,
                                downloadedBytes  = bytesRead,
                                totalBytes       = totalBytes,
                                speedBytesPerSec = parseSpeedString(speed),
                            )
                        },
                        // Map integrity-walk progress onto the SYNC stage's 0.2..0.7
                        // sub-range. The actual download progress takes over from
                        // 0.7 upward via the Downloading state above. Without
                        // this, the progress bar froze at 20% during the MD5
                        // walk on 1000-file modpacks -- 5-30s of perceived hang.
                        verifyUI = { verified, total ->
                            if (!isActive) return@processSession
                            val fraction = if (total > 0) verified.toFloat() / total else 0f
                            setStage(PrepareStage.SYNC, 0.2f + 0.5f * fraction)
                        },
                    )
                }

                // 4. Java
                setStage(PrepareStage.JVM, 0.9f)
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    javaManagerService.getJavaPath(server.version)
                }

                // 5. Launch
                setStage(PrepareStage.LAUNCH, 0.95f)
                ActionRing.record("Game running: ${server.name}")
                emit(LaunchLogEvent.Launching)

                val process = launcherService.launchClientWithLogs(
                    sessionData = session,
                    serverProfile = server,
                    clientRootPath = clientDir,
                    javaExecutablePath = javaPath,
                    allocatedMemoryMB = settings.memoryMB,
                ) { text, type ->
                    emit(LaunchLogEvent.ProcessOutput(text, type))
                }

                runningProcess = process
                _state.value = LaunchState.GameRunning(process)

                val exitCode = process.waitFor()
                runningProcess = null
                ActionRing.record("Game exited: ${server.name} (code $exitCode)")

                if (exitCode != 0) {
                    fail(LaunchError.ExitCode(exitCode))
                } else {
                    _state.value = LaunchState.Idle
                }

            } catch (e: Exception) {
                runningProcess = null
                if (e !is CancellationException) {
                    logger.error("Launch flow failed for {}", server.name, e)
                    fail(LaunchError.Internal(e.message ?: ""), e)
                } else {
                    _state.value = LaunchState.Idle
                }
            }
        }
    }

    /**
     * Pack-centric launch path. Equivalent to [launch] for the
     * Hivens mirror world: takes a [PackInstance] from the local
     * Library, skips SC auth + per-launch asset re-sync (mirror
     * packs are static + already on disk after install), resolves
     * Java from the manifest's declared MC version, and spawns via
     * [ILauncherService.launchPackClient].
     *
     * Re-entry guard, MDC tagging and abort semantics mirror [launch]
     * exactly so the existing UI surfaces ([LaunchControlPanel],
     * `GameConsoleService`) plug in unchanged.
     *
     * Behaviour notes:
     * - No SC auth and no SmrtSyncService call: the install flow is
     *   responsible for materialising the instance, and an explicit
     *   "Update pack" Library action will run sync separately later.
     * - When [PackInstance.cachedManifest] is null (instance created
     *   before the field existed), a one-time mirror fetch fills it
     *   in and the result is written back via [IPackRepository.put].
     *   Subsequent Play clicks read from cache.
     * - [PackInstance.lastPlayedEpochOrZero] is bumped on successful
     *   spawn so the Library sort-by-recently-played stays accurate.
     */
    fun launchPackInstance(
        currentSession: SessionData,
        packInstance: PackInstance,
    ) {
        synchronized(launchLock) {
            if (_state.value !is LaunchState.Idle &&
                _state.value !is LaunchState.Error) return
            _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
        }

        val launchId = UUID.randomUUID().toString().take(8)

        launchJob = appScope.launch(MDCContext(mapOf("launchId" to launchId))) {
            val settings = settingsService.getSettings()

            try {
                _state.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)

                emit(LaunchLogEvent.SessionStarted)
                emit(LaunchLogEvent.AppBanner)
                // Mirror packs are public read; surfacing the offline
                // flag here would be misleading -- pack-centric Play
                // does not need network at all when cachedManifest is
                // populated.
                emit(LaunchLogEvent.TargetServer(packInstance.displayName, offline = false))

                ActionRing.record("Launching pack: ${packInstance.displayName} (launchId=$launchId)")

                // 1. Resolve the manifest snapshot. Stored on the
                // instance after install; one-shot fetch + write-back
                // covers instances that predate the field.
                setStage(PrepareStage.SYNC, 0.3f)
                val (manifestSnapshot, refreshedInstance) =
                    resolveOrFetchManifest(packInstance)

                // 2. Java. Same JavaManager path as the SC flow --
                // pack manifest's declared MC version drives the
                // managed Liberica selection.
                setStage(PrepareStage.JVM, 0.7f)
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    javaManagerService.getJavaPath(manifestSnapshot.minecraftVersion)
                }

                // 3. Spawn. Pack-centric clientRoot lives under
                // `<dataDir>/instances/<instanceDirName>`, not under
                // `<dataDir>/clients/<serverId>` like the SC flow.
                setStage(PrepareStage.LAUNCH, 0.95f)
                ActionRing.record("Game running: ${packInstance.displayName}")
                emit(LaunchLogEvent.Launching)

                val clientDir = dataDirectory
                    .resolve("instances")
                    .resolve(refreshedInstance.instanceDirName)
                if (!Files.exists(clientDir)) {
                    fail(LaunchError.OfflineNoClient)
                    return@launch
                }

                val process = launcherService.launchPackClient(
                    sessionData          = currentSession,
                    manifest             = manifestSnapshot,
                    runtime              = refreshedInstance.runtime,
                    clientRootPath       = clientDir,
                    javaExecutablePath   = javaPath,
                    allocatedMemoryMB    = settings.memoryMB,
                    displayName          = refreshedInstance.displayName,
                ) { text, type ->
                    emit(LaunchLogEvent.ProcessOutput(text, type))
                }

                runningProcess = process
                _state.value = LaunchState.GameRunning(process)

                // Bump lastPlayed *after* the process has actually
                // spawned, not before -- a failed spawn should not
                // pollute the recent-played sort.
                runCatching {
                    packRepository.put(
                        refreshedInstance.copy(
                            lastPlayedEpochOrZero = Instant.now().epochSecond,
                        ),
                    )
                }.onFailure { logger.warn("Failed to bump lastPlayed for ${refreshedInstance.id}", it) }

                val exitCode = process.waitFor()
                runningProcess = null
                ActionRing.record("Game exited: ${refreshedInstance.displayName} (code $exitCode)")

                if (exitCode != 0) {
                    fail(LaunchError.ExitCode(exitCode))
                } else {
                    _state.value = LaunchState.Idle
                }

            } catch (e: Exception) {
                runningProcess = null
                if (e !is CancellationException) {
                    logger.error("Pack launch flow failed for {}", packInstance.displayName, e)
                    fail(LaunchError.Internal(e.message ?: ""), e)
                } else {
                    _state.value = LaunchState.Idle
                }
            }
        }
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
        )
        val refreshed = instance.copy(cachedManifest = snapshot)
        runCatching { packRepository.put(refreshed) }
            .onFailure { logger.warn("Failed to persist cachedManifest for ${instance.id}", it) }
        return snapshot to refreshed
    }

    /**
     * Stops the in-flight launch. If the game process has already
     * spawned, sends SIGTERM via [Process.destroy] before resetting
     * state -- canceling the coroutine alone would orphan the
     * spawned Process and the next [launch] click would happily
     * spawn a second game.
     */
    fun abort() {
        val proc = runningProcess
        runningProcess = null
        runCatching { proc?.destroy() }
        launchJob?.cancel()
        _state.value = LaunchState.Idle
    }

    fun clearError() {
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
     * Best-effort parse of FileDownloadService's pre-formatted speed string
     * (`"2.5 MB/s"`, `"812 KB/s"`, etc.) into bytes/second. The UI side
     * formats freshly from this number so locale conventions stay correct;
     * the raw string from FileDownloadService is in the launcher's locale
     * which may not match the user's UI locale.
     *
     * Returns 0 on unparseable input -- the UI just shows no speed in that
     * case rather than displaying a nonsensical figure.
     */
    private fun parseSpeedString(speed: String): Long {
        val trimmed = speed.trim()
        val match = Regex("""([\d.,]+)\s*([KMG]?)B?/s""", RegexOption.IGNORE_CASE).find(trimmed) ?: return 0L
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1_024L
            "M" -> 1_048_576L
            "G" -> 1_073_741_824L
            else -> 1L
        }
        return (value * multiplier).toLong()
    }
}
