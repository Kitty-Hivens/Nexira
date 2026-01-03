package hivens.launcher

import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.LauncherLogType
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
 * Реализация сервиса запуска клиента Minecraft.
 *
 * <p>Действует как фасад, координируя работу компонентов подготовки окружения ([EnvironmentPreparer]),
 * сборки classpath ([ClasspathProvider]) и формирования командной строки ([GameCommandBuilder]).</p>
 */
class LauncherService(
    manifestProcessor: IManifestProcessorService,
    private val profileManager: ProfileManager,
    private val javaManager: JavaManagerService,
    private val envPreparer: EnvironmentPreparer
) : ILauncherService {

    private val log = LoggerFactory.getLogger(LauncherService::class.java)
    private val classpathProvider = ClasspathProvider(manifestProcessor)
    private val commandBuilder = GameCommandBuilder()
    private val logHandler = ProcessLogHandler()

    /**
     * Запускает клиент с перехватом логов.
     *
     * @see [ILauncherService.launchClientWithLogs]
     */
    @Throws(IOException::class)
    override suspend fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val profile: InstanceProfile = profileManager.getProfile(serverProfile.assetDir)
        val version = serverProfile.version

        // 1. Стратегия выделения памяти
        var memory = if (profile.memoryMb > 0) profile.memoryMb else allocatedMemoryMB
        if (memory < 768) memory = 1024

        // 2. Определение пути к Java
        val javaExec: String = resolveJavaPath(profile, javaExecutablePath, version)

        log.info("Инициализация сессии: {}, Java: {}, Heap: {}MB", serverProfile.name, javaExec, memory)
        onLog("Запуск ${serverProfile.name}...", LauncherLogType.INFO)

        // 3. Подготовка нативных библиотек и ассетов
        val nativesDir = commandBuilder.getNativesDir(version)
        envPreparer.prepareNatives(clientRootPath, nativesDir, version)
        envPreparer.prepareAssets(clientRootPath, "assets-$version.zip")

        // 4. Сборка Classpath
        val manifest = sessionData.fileManifest ?: FileManifest()
        val excludedModules = if (version == "1.21.1") commandBuilder.getNeoForgeModules() else emptyList()
        val classpath = classpathProvider.buildClasspath(clientRootPath, manifest, excludedModules)

        // 5. Сборка команды запуска
        val command = commandBuilder.build(
            javaExec, memory, clientRootPath,
            serverProfile, sessionData, profile,
            classpath
        )

        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)

        onLog("CMD: ${java.lang.String.join(" ", command)}", LauncherLogType.INFO)

        val process = pb.start()

        // 6. Подключение перехватчика логов
        logHandler.attach(process, onLog)

        return process
    }

    override suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process {
        return launchClientWithLogs(
            sessionData, serverProfile, clientRootPath, javaExecutablePath, allocatedMemoryMB
        ) { _, _ -> /* Логи игнорируются */ }
    }

    /**
     * Выбирает подходящий Java Runtime.
     * Приоритет: Настройка профиля -> Управляемая Java (JavaManager) -> Системная Java.
     */
    private suspend fun resolveJavaPath(profile: InstanceProfile, defaultPath: Path, version: String): String {
        if (!profile.javaPath.isNullOrEmpty()) return profile.javaPath!!
        runCatching {
            val managedPath = javaManager.getJavaPath(version)
            if (Files.exists(managedPath)) return managedPath.toString()
        }
        if (Files.exists(defaultPath)) return defaultPath.toString()
        return "java"
    }
}
