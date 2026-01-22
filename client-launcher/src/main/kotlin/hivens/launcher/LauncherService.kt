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
 * Implementation of the Minecraft client launch service.
 *
 * <p>Acts as a facade, coordinating the work of environment preparation components ([EnvironmentPreparer]),
 * classpath build ([ClasspathProvider]) and command line build ([GameCommandBuilder]).</p>
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
     * Launches a client with log interception.
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

        // 1. Memory allocation strategy
        var memory = if (profile.memoryMb > 0) profile.memoryMb else allocatedMemoryMB
        if (memory < 768) memory = 1024

        // 2. Determining the path to Java
        val javaExec: String = resolveJavaPath(profile, javaExecutablePath, version)

        log.info("Session initialization: {}, Java: {}, Heap: {}MB", serverProfile.name, javaExec, memory)
        onLog("Running ${serverProfile.name}...", LauncherLogType.INFO)

        // 3. Preparation of native libraries and assets
        val nativesDir = commandBuilder.getNativesDir(version)
        envPreparer.prepareNatives(clientRootPath, nativesDir, version)
        envPreparer.prepareAssets(clientRootPath, "assets-$version.zip")

        // 4. Classpath assembly
        val manifest = sessionData.fileManifest ?: FileManifest()
        val excludedModules = emptyList<String>()
        val classpath = classpathProvider.buildClasspath(clientRootPath, manifest, excludedModules)

        // 5. Assembling the launch command
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

        // 6. Connecting a log interceptor
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
        ) { _, _ -> /* Logs are ignored */ }
    }

    /**
     * Selects the appropriate Java Runtime.
     * Priority: Profile Setup -> Managed Java (JavaManager) -> System Java.
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
