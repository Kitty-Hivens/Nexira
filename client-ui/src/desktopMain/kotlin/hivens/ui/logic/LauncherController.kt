package hivens.ui.logic

import hivens.core.api.interfaces.*
import hivens.core.api.model.ServerProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.diag.ActionRing
import hivens.launcher.CredentialsManager
import hivens.launcher.ManifestCache
import hivens.launcher.ProfileManager
import hivens.ui.easter.AprilFoolsProgress
import hivens.ui.i18n.I18n
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LauncherController : KoinComponent {

    private val logger = LoggerFactory.getLogger(LauncherController::class.java)

    private val authService: IAuthService by inject()
    private val credentialsManager: CredentialsManager by inject()
    private val settingsService: ISettingsService by inject()
    private val downloadService: IFileDownloadService by inject()
    private val javaManagerService: IJavaManager by inject()
    private val launcherService: ILauncherService by inject()
    private val manifestProcessor: IManifestProcessorService by inject()
    private val manifestCache: ManifestCache by inject()
    private val profileManager: ProfileManager by inject()
    private val dataDirectory: Path by inject()

    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        // Without the lock two parallel callers (UI double-click, tray-
        // launch racing dashboard-launch) could both observe Idle, both
        // pass the gate, both assign launchJob, and produce two in-flight
        // game spawns -- of which only the second is tracked for abort().
        // Claim the state slot under the lock; the coroutine still runs
        // outside the lock so the gate isn't held during the long flow.
        synchronized(launchLock) {
            if (_state.value !is LaunchState.Idle &&
                _state.value !is LaunchState.Error) return
            _state.value = LaunchState.Prepare("", 0.0f)
        }

        // Tag every log line emitted during this launch attempt with a stable
        // launchId so a user dump can be sliced per-play-click via
        // `grep launchId=abcd1234 *.log`. MDCContext (from
        // kotlinx-coroutines-slf4j) propagates the value across every
        // dispatcher hop the launch flow takes, including the downstream
        // FileDownloadService coroutines and LauncherService.
        val launchId = UUID.randomUUID().toString().take(8)

        launchJob = appScope.launch(kotlinx.coroutines.slf4j.MDCContext(mapOf("launchId" to launchId))) {
            // Capture strings at launch time so the whole pipeline uses one locale
            val s = I18n.s
            val settings = settingsService.getSettings()
            val isOffline = settings.isOfflineMode

            try {
                _state.value = LaunchState.Prepare(s.stateInit, 0.0f)

                // Start new session -- adds divider and opens auto-save file
                GameConsoleService.startSession()
                GameConsoleService.append("${s.appName}...", LogType.INFO)
                GameConsoleService.append("-> ${server.name}" + if (isOffline) " [OFFLINE]" else "", LogType.INFO)

                ActionRing.record("Launching: ${server.name} (launchId=$launchId)")

                // 1. Auth -- skip in offline mode
                updateProgress(0.1f, s.stateAuth)
                var session = currentSession
                val targetServerId = server.assetDir

                if (isOffline) {
                    GameConsoleService.append(s.stateOfflineSkipAuth, LogType.WARN)
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
                            GameConsoleService.append(s.authSuccess(session.uuid), LogType.INFO)
                        } else {
                            GameConsoleService.append(s.stateNoPassword, LogType.WARN)
                        }
                    } catch (_: hivens.core.api.TwoFactorRequiredException) {
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
                            // No cached manifest and no fresh login. Continuing
                            // hits "File manifest is empty!" deep in
                            // processSession -- cryptic for the user. Throw
                            // with the same string the 2FA dialog uses so the
                            // outer LaunchState.Error renders an actionable
                            // message ("re-login from the form"), not a
                            // misleading internal one. Caught by the
                            // top-level handler at the bottom of this fn.
                            ActionRing.record("Launch: 2FA + no cached manifest for $targetServerId -- re-login required")
                            throw IllegalStateException(s.auth2faExpired)
                        }
                    } catch (e: Exception) {
                        GameConsoleService.append("${s.stateAuthFail}: ${e.message}", LogType.WARN)
                        // If auth fails, and we're NOT in offline mode, we still try to continue
                        // with the existing session (graceful degradation)
                    }
                }

                // 2. Ignored files
                val ignoredFiles = calculateIgnoredFiles(server)

                // 3. Download -- skip in offline mode if client exists
                updateProgress(0.2f, s.stateSync)
                val clientDir = dataDirectory.resolve("clients").resolve(targetServerId)
                if (!Files.exists(clientDir)) Files.createDirectories(clientDir)

                if (isOffline) {
                    // In offline mode, skip file sync but verify client exists.
                    // .use{} closes the directory stream; without it the OS
                    // file handle leaks until GC eventually collects the stream.
                    val hasClient = Files.exists(clientDir) &&
                        Files.list(clientDir).use { it.count() > 0 }
                    if (!hasClient) {
                        _state.value = LaunchState.Error(s.stateOfflineNoClient)
                        GameConsoleService.append(s.stateOfflineNoClient, LogType.ERROR)
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
                            _state.value = LaunchState.Error(s.stateOfflineNoManifest)
                            GameConsoleService.append(s.stateOfflineNoManifest, LogType.ERROR)
                            return@launch
                        }
                    }
                    GameConsoleService.append(s.stateOfflineSkipSync, LogType.INFO)
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
                            // April Fools: display progress may regress, actual download is unaffected
                            val displayProgress = AprilFoolsProgress.wrap(bytesRead, totalBytes)
                            _state.value = LaunchState.Downloading(
                                fileName        = "${s.fileDownloading(total).substringBefore("(")}$current/$total",
                                currentFileIdx  = current,
                                totalFiles      = total,
                                downloadedBytes = bytesRead,
                                totalBytes      = totalBytes,
                                speedStr        = speed,
                                progress        = displayProgress
                            )
                        },
                    )
                    AprilFoolsProgress.reset()
                }

                // 4. Java
                updateProgress(0.9f, s.stateJvm)
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    javaManagerService.getJavaPath(server.version)
                }

                // 5. Launch
                ActionRing.record("Game running: ${server.name}")
                GameConsoleService.append(s.stateLaunching, LogType.INFO)

                val process = launcherService.launchClientWithLogs(
                    sessionData = session,
                    serverProfile = server,
                    clientRootPath = clientDir,
                    javaExecutablePath = javaPath,
                    allocatedMemoryMB = settings.memoryMB
                ) { text, type ->
                    val uiType = when (type) {
                        LauncherLogType.INFO  -> LogType.INFO
                        LauncherLogType.WARN  -> LogType.WARN
                        LauncherLogType.ERROR -> LogType.ERROR
                    }
                    GameConsoleService.append(text, uiType)
                }

                runningProcess = process
                _state.value = LaunchState.GameRunning(process)

                val exitCode = process.waitFor()
                runningProcess = null
                ActionRing.record("Game exited: ${server.name} (code $exitCode)")

                if (exitCode != 0) {
                    _state.value = LaunchState.Error(s.stateExitCode(exitCode))
                    GameConsoleService.show()
                } else {
                    _state.value = LaunchState.Idle
                }

            } catch (e: Exception) {
                runningProcess = null
                if (e !is CancellationException) {
                    logger.error("Launch flow failed for {}", server.name, e)
                    _state.value = LaunchState.Error(s.stateError(e.message ?: ""), e)
                    GameConsoleService.show()
                } else {
                    _state.value = LaunchState.Idle
                }
            }
        }
    }

    /**
     * Stops whatever launch flow is currently running. If the game process
     * has already spawned, send SIGTERM via [Process.destroy] and reset state.
     * Previously this cancelled the coroutine but left the spawned Process
     * orphaned -- the launcher said Idle while the game kept running, and
     * the next launch() click would happily try to spawn a second game.
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

    private fun updateProgress(progress: Float, step: String) {
        _state.value = LaunchState.Prepare(step, progress)
    }

    private fun calculateIgnoredFiles(server: ServerProfile): Set<String> {
        val userState = profileManager.getProfile(server.assetDir).optionalModsState
        return manifestProcessor.calculateIgnoredFiles(server, userState)
    }
}
