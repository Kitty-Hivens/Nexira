package hivens.launcher

import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Основной сервис, управляющий жизненным циклом запуска игрового клиента.
 *
 * Реализует паттерн **Facade**, скрывая сложность инициализации и координации
 * следующих подсистем:
 * * **Environment**: Подготовка файловой системы (natives, assets).
 * * **Dependency Resolution**: Построение classpath.
 * * **Command Factory**: Генерация аргументов процесса.
 * * **IO Handling**: Перехват логов процесса.
 */
class LauncherService(
    manifestProcessor: IManifestProcessorService,
    private val profileManager: ProfileManager,
    private val javaManager: JavaManagerService
) : ILauncherService {

    private val log = LoggerFactory.getLogger(LauncherService::class.java)

    // Инициализация компонентов-делегатов
    private val envPreparer = EnvironmentPreparer()
    private val classpathProvider = ClasspathProvider(manifestProcessor)
    private val commandBuilder = GameCommandBuilder()
    private val logHandler = ProcessLogHandler()

    /**
     * Запускает клиент Minecraft в отдельном процессе ОС.
     *
     * **Последовательность операций:**
     * 1. Резолвинг Java Runtime (пользовательская или управляемая).
     * 2. Распаковка нативных библиотек во временную директорию.
     * 3. Сборка Classpath на основе манифеста файлов.
     * 4. Формирование командной строки.
     * 5. Запуск процесса и аттачмент логгера.
     *
     * @param sessionData Данные сессии авторизации.
     * @param serverProfile Профиль выбранного сервера.
     * @param clientRootPath Корневая директория клиента.
     * @param javaExecutablePath Путь к системной Java (fallback).
     * @param allocatedMemoryMB Лимит RAM (Heap Size).
     * @param onLog Callback для стриминга логов.
     *
     * @throws IOException При ошибках ввода-вывода или невалидной конфигурации.
     */
    @Throws(IOException::class)
    fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val profile: InstanceProfile = profileManager.getProfile(serverProfile.assetDir)
        val version = serverProfile.version
        
        // 1. Memory Allocation Strategy
        var memory = if (profile.memoryMb > 0) profile.memoryMb else allocatedMemoryMB
        if (memory < 768) memory = 1024

        // 2. Java Runtime Resolution
        val javaExec: String = resolveJavaPath(profile, javaExecutablePath, version)
        
        log.info("Инициализация сессии: {}, Java: {}, Heap: {}MB", serverProfile.name, javaExec, memory)
        onLog("Запуск ${serverProfile.name}...", LauncherLogType.INFO)

        // 3. Environment Preparation
        val nativesDir = commandBuilder.getNativesDir(version)
        envPreparer.prepareNatives(clientRootPath, nativesDir, version)
        envPreparer.prepareAssets(clientRootPath, "assets-$version.zip")

        // 4. Classpath Construction
        val manifest = sessionData.fileManifest ?: FileManifest()
        val excludedModules = if (version == "1.21.1") commandBuilder.getNeoForgeModules() else emptyList()
        
        val classpath = classpathProvider.buildClasspath(clientRootPath, manifest, excludedModules)

        // 5. Command Assembly
        val command = commandBuilder.build(
            javaExec, memory, clientRootPath, 
            serverProfile, sessionData, profile, 
            classpath
        )

        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)

        onLog("LAUNCH ARGS: ${java.lang.String.join(" ", command)}", LauncherLogType.INFO)

        val process = pb.start()
        
        // 6. IO Attachment
        logHandler.attach(process, onLog)

        return process
    }

    override fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process {
        return launchClientWithLogs(sessionData, serverProfile, clientRootPath, javaExecutablePath, allocatedMemoryMB) { _, _ -> }
    }

    private fun resolveJavaPath(profile: InstanceProfile, defaultPath: Path, version: String): String {
        if (!profile.javaPath.isNullOrEmpty()) return profile.javaPath!!
        runCatching {
            val managedPath = javaManager.getJavaPath(version)
            if (Files.exists(managedPath)) return managedPath.toString()
        }
        if (Files.exists(defaultPath)) return defaultPath.toString()
        return "java"
    }
}
