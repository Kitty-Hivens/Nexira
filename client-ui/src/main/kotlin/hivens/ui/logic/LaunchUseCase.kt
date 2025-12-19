package hivens.ui.logic

import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.FileDownloadService
import hivens.launcher.LauncherDI
import hivens.launcher.LauncherLogType
import hivens.launcher.LauncherService
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class LaunchUseCase(private val di: LauncherDI) {

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
                onProgress(0.1f, "Авторизация...")
                GameConsoleService.append("Шаг 1: Авторизация...", LogType.INFO)

                var session = currentSession
                val targetServerId = server.assetDir

                try {
                    val pass = di.credentialsManager.load()?.decryptedPassword ?: session.cachedPassword
                    if (!pass.isNullOrEmpty()) {
                        session = di.authService.login(session.playerName, pass, targetServerId)
                        GameConsoleService.append("Успешный вход. UUID: ${session.uuid}", LogType.INFO)
                    } else {
                        GameConsoleService.append("Пароль не найден, пробуем старую сессию.", LogType.WARN)
                    }
                } catch (e: Exception) {
                    GameConsoleService.append("Ошибка входа (оффлайн?): ${e.message}", LogType.WARN)
                }

                onSessionUpdated(session)

                // 2. Скачивание
                onProgress(0.2f, "Синхронизация файлов...")
                GameConsoleService.append("Шаг 2: Проверка файлов...", LogType.INFO)

                val clientDir = di.dataDirectory.resolve("clients").resolve(targetServerId)
                if (!Files.exists(clientDir)) Files.createDirectories(clientDir)
                val downloader = di.downloadService as FileDownloadService

                downloader.processSession(
                    session = session,
                    serverId = targetServerId,
                    targetDir = clientDir,
                    extraCheckSum = server.extraCheckSum,
                    ignoredFiles = emptySet(),
                    messageUI = {},
                    progressUI = { current: Int, total: Int ->
                        if (total > 0) {
                            val p = 0.2f + (current.toFloat() / total.toFloat() * 0.7f)
                            onProgress(p, "Загрузка: $current / $total")
                        }
                    }
                )
                GameConsoleService.append("Файлы синхронизированы.", LogType.INFO)

                // 3. Запуск Java
                onProgress(0.9f, "Подготовка JVM...")
                val settings = di.settingsService.getSettings()
                val javaPath = if (!settings.javaPath.isNullOrEmpty()) {
                    Path.of(settings.javaPath!!)
                } else {
                    di.javaManagerService.getJavaPath(server.version)
                }
                val memory = settings.memoryMB

                GameConsoleService.append("Шаг 3: Запуск процесса...", LogType.INFO)
                GameConsoleService.append("Java: $javaPath", LogType.INFO)

                val launcher = di.launcherService as LauncherService

                val process = launcher.launchClientWithLogs(
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
                    GameConsoleService.append("Игра посыпалась (Код: $exitCode)", LogType.ERROR)
                    GameConsoleService.show()
                } else {
                    GameConsoleService.append("Игра закрылась в страхе.", LogType.INFO)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                GameConsoleService.append("КРИТИЧЕСКАЯ ОШИБКА:", LogType.ERROR)
                GameConsoleService.append(e.toString(), LogType.ERROR)
                GameConsoleService.show()
            }
        }
    }
}
