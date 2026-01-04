package hivens.ui.logic

import hivens.core.api.interfaces.*
import hivens.core.api.model.ServerProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.JavaManagerService
import hivens.launcher.ProfileManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Files
import java.nio.file.Path

class LauncherController : KoinComponent {

    private val authService: IAuthService by inject()
    private val credentialsManager: CredentialsManager by inject()
    private val settingsService: ISettingsService by inject()
    private val downloadService: IFileDownloadService by inject()
    private val javaManagerService: JavaManagerService by inject()
    private val launcherService: ILauncherService by inject()
    private val manifestProcessor: IManifestProcessorService by inject()
    private val profileManager: ProfileManager by inject()
    private val dataDirectory: Path by inject()

    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launchJob: Job? = null

    // Добавили onSessionRefreshed для совместимости с Main.kt
    fun launch(
        currentSession: SessionData,
        server: ServerProfile,
        onSessionRefreshed: ((SessionData) -> Unit)? = null
    ) {
        if (_state.value is LaunchState.Prepare || _state.value is LaunchState.Downloading) return

        launchJob = appScope.launch {
            try {
                _state.value = LaunchState.Prepare("Инициализация...", 0.0f)
                GameConsoleService.clear()
                GameConsoleService.append("Запуск Minecraft...", LogType.INFO)
                GameConsoleService.append("Цель: ${server.name}", LogType.INFO)

                // 1. Авторизация
                updateProgress(0.1f, "Авторизация...")
                var session = currentSession
                val targetServerId = server.assetDir

                try {
                    val pass = credentialsManager.load()?.cachedPassword ?: session.cachedPassword
                    if (!pass.isNullOrEmpty()) {
                        session = authService.login(session.playerName, pass, targetServerId)
                        onSessionRefreshed?.invoke(session) // Сообщаем UI об обновлении сессии
                        GameConsoleService.append("Успешный вход. UUID: ${session.uuid}", LogType.INFO)
                    } else {
                        GameConsoleService.append("Пароль не найден, используем текущую сессию.", LogType.WARN)
                    }
                } catch (e: Exception) {
                    GameConsoleService.append("Ошибка авторизации (оффлайн?): ${e.message}", LogType.WARN)
                }

                // 2. Игнорируемые файлы
                val ignoredFiles = calculateIgnoredFiles(server)

                // 3. Скачивание
                updateProgress(0.2f, "Синхронизация файлов...")
                val clientDir = dataDirectory.resolve("clients").resolve(targetServerId)
                if (!Files.exists(clientDir)) Files.createDirectories(clientDir)

                downloadService.processSession(
                    session = session,
                    serverId = targetServerId,
                    targetDir = clientDir,
                    extraCheckSum = server.extraCheckSum,
                    ignoredFiles = ignoredFiles,
                    messageUI = { /* log */ },
                    progressUI = { current, total, bytesRead, totalBytes, speed ->
                        if (!isActive) return@processSession

                        val progressValue = if (totalBytes > 0) bytesRead.toFloat() / totalBytes.toFloat() else 0f
                        _state.value = LaunchState.Downloading(
                            fileName = "Файл $current/$total",
                            currentFileIdx = current,
                            totalFiles = total,
                            downloadedBytes = bytesRead,
                            totalBytes = totalBytes,
                            speedStr = speed,
                            progress = progressValue
                        )
                    }
                )

                // 4. Java
                updateProgress(0.9f, "Подготовка JVM...")
                val settings = settingsService.getSettings()
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    javaManagerService.getJavaPath(server.version)
                }

                // 5. Запуск
                GameConsoleService.append("Запуск процесса...", LogType.INFO)
                val process = launcherService.launchClientWithLogs(
                    sessionData = session,
                    serverProfile = server,
                    clientRootPath = clientDir,
                    javaExecutablePath = javaPath,
                    allocatedMemoryMB = settings.memoryMB
                ) { text, type ->
                    val uiType = when(type) {
                        LauncherLogType.INFO -> LogType.INFO
                        LauncherLogType.WARN -> LogType.WARN
                        LauncherLogType.ERROR -> LogType.ERROR
                    }
                    GameConsoleService.append(text, uiType)
                }

                _state.value = LaunchState.GameRunning(process)

                // Ждем закрытия (в фоне)
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    _state.value = LaunchState.Error("Игра закрылась с кодом $exitCode")
                    GameConsoleService.show()
                } else {
                    _state.value = LaunchState.Idle
                }

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    e.printStackTrace()
                    _state.value = LaunchState.Error("Ошибка: ${e.message}", e)
                    GameConsoleService.show()
                } else {
                    _state.value = LaunchState.Idle
                }
            }
        }
    }

    fun abort() {
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
        val availableMods = manifestProcessor.getOptionalModsForClient(server)
        if (availableMods.isEmpty()) return emptySet()

        val ignored = HashSet<String>()
        val userProfile = profileManager.getProfile(server.assetDir)
        val userState = userProfile.optionalModsState

        for (mod in availableMods) {
            val isEnabled = userState[mod.id] ?: mod.isDefault
            if (!isEnabled) {
                ignored.addAll(mod.jars)
                if (mod.infoFile != null) ignored.add(mod.infoFile!!)
            }
        }
        return ignored
    }
}
