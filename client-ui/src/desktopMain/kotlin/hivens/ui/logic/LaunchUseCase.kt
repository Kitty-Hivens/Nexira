package hivens.ui.logic

import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.core.data.LauncherLogType
import hivens.launcher.CredentialsManager
import hivens.launcher.JavaManagerService
import hivens.launcher.ProfileManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сценарий использования (UseCase) для запуска игры.
 */
class LaunchUseCase(
    private val authService: IAuthService,
    private val credentialsManager: CredentialsManager,
    private val settingsService: ISettingsService,
    private val downloadService: IFileDownloadService,
    private val javaManagerService: JavaManagerService,
    private val launcherService: ILauncherService,
    private val manifestProcessor: IManifestProcessorService,
    private val profileManager: ProfileManager,
    private val dataDirectory: Path
) {

    suspend fun launch(
        currentSession: SessionData,
        server: ServerProfile,
        onProgress: (Float, String) -> Unit,
        onSessionUpdated: (SessionData) -> Unit
    ) {
        GameConsoleService.clear()
        GameConsoleService.append("Запуск Minecraft...", LogType.INFO)
        GameConsoleService.append("Цель: ${server.name}", LogType.INFO)

        withContext(Dispatchers.IO) {
            try {
                // 1. Авторизация
                onProgress(0.1f, "Авторизация...")
                GameConsoleService.append("Шаг 1: Авторизация...", LogType.INFO)

                var session = currentSession
                val targetServerId = server.assetDir

                try {
                    val pass = credentialsManager.load()?.cachedPassword ?: session.cachedPassword
                    if (!pass.isNullOrEmpty()) {
                        session = authService.login(session.playerName, pass, targetServerId)
                        GameConsoleService.append("Успешный вход. UUID: ${session.uuid}", LogType.INFO)
                    } else {
                        GameConsoleService.append("Пароль не найден, пробуем старую сессию.", LogType.WARN)
                    }
                } catch (e: Exception) {
                    GameConsoleService.append("Ошибка входа (оффлайн?): ${e.message}", LogType.WARN)
                }

                onSessionUpdated(session)

                // 2. Расчет опциональных модов (DEBUG)
                GameConsoleService.append("--- Проверка модификаций ---", LogType.INFO)
                val ignoredFiles = calculateIgnoredFiles(server)

                // 3. Синхронизация файлов
                onProgress(0.2f, "Синхронизация файлов...")
                GameConsoleService.append("Шаг 2: Проверка файлов...", LogType.INFO)

                val clientDir = dataDirectory.resolve("clients").resolve(targetServerId)
                if (!Files.exists(clientDir)) Files.createDirectories(clientDir)

                downloadService.processSession(
                    session = session,
                    serverId = targetServerId,
                    targetDir = clientDir,
                    extraCheckSum = server.extraCheckSum,
                    ignoredFiles = ignoredFiles, // <-- Передаем вычисленный список
                    messageUI = {},
                    progressUI = { current: Int, total: Int ->
                        if (total > 0) {
                            val p = 0.2f + (current.toFloat() / total.toFloat() * 0.7f)
                            onProgress(p, "Загрузка: $current / $total")
                        }
                    }
                )
                GameConsoleService.append("Файлы синхронизированы.", LogType.INFO)

                // 4. Подготовка окружения Java
                onProgress(0.9f, "Подготовка JVM...")
                val settings = settingsService.getSettings()
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    javaManagerService.getJavaPath(server.version)
                }
                val memory = settings.memoryMB

                GameConsoleService.append("Шаг 3: Запуск процесса...", LogType.INFO)

                // 5. Запуск процесса
                val process = launcherService.launchClientWithLogs(
                    sessionData = session,
                    serverProfile = server,
                    clientRootPath = clientDir,
                    javaExecutablePath = javaPath,
                    allocatedMemoryMB = memory
                ) { text, type ->
                    val uiType = when(type) {
                        LauncherLogType.INFO -> LogType.INFO
                        LauncherLogType.WARN -> LogType.WARN
                        LauncherLogType.ERROR -> LogType.ERROR
                    }
                    GameConsoleService.append(text, uiType)
                }

                onProgress(1.0f, "Игра запущена")
                GameConsoleService.append("--- Minecraft запущен ---", LogType.INFO)

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    GameConsoleService.append("Игра упала с ошибкой (Код: $exitCode)", LogType.ERROR)
                    GameConsoleService.show()
                } else {
                    GameConsoleService.append("Игра закрылась корректно.", LogType.INFO)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                GameConsoleService.append("КРИТИЧЕСКАЯ ОШИБКА:", LogType.ERROR)
                GameConsoleService.append(e.toString(), LogType.ERROR)
                GameConsoleService.show()
            }
        }
    }

    /**
     * Вычисляет список файлов, которые нужно игнорировать (не качать и удалить).
     */
    private fun calculateIgnoredFiles(server: ServerProfile): Set<String> {
        val availableMods = manifestProcessor.getOptionalModsForClient(server)
        if (availableMods.isEmpty()) return emptySet()

        val ignored = HashSet<String>()
        val userProfile = profileManager.getProfile(server.assetDir)
        val userState = userProfile.optionalModsState

        for (mod in availableMods) {
            // Если настройки нет в профиле игрока, берем дефолтное значение мода (из поля selected/default)
            val isEnabled = userState[mod.id] ?: mod.isDefault

            if (!isEnabled) {
                ignored.addAll(mod.jars)
                if (mod.infoFile != null) ignored.add(mod.infoFile!!)
            }
        }

        return ignored
    }
}
