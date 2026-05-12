package hivens.launcher

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
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
 * Acts as a facade, coordinating the work of [EnvironmentPreparer] (natives + assets),
 * [ClasspathProvider] (manifest → classpath), [GameCommandBuilder] (version-specific JVM
 * command) and [ProcessLogHandler] (stdout/stderr interception). All collaborators are
 * supplied via constructor injection so that this service can be unit-tested in isolation.
 */
internal class LauncherService(
    private val profileManager: ProfileManager,
    private val javaManager: IJavaManager,
    private val envPreparer: EnvironmentPreparer,
    private val classpathProvider: ClasspathProvider,
    private val commandBuilder: GameCommandBuilder,
    private val logHandler: ProcessLogHandler
) : ILauncherService {

    private val log = LoggerFactory.getLogger(LauncherService::class.java)

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
        val memory = normalizeMemory(profile.memoryMb, allocatedMemoryMB)

        // 2. Determining the path to Java
        val javaExec: String = resolveJavaPath(javaManager, profile, javaExecutablePath, version)

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

    internal companion object {
        /**
         * Memory allocation rule: profile's per-instance value wins when positive,
         * otherwise the launcher's globally allocated value is used. Anything below
         * 768 MB is bumped to 1024 MB to keep modded clients viable.
         */
        internal fun normalizeMemory(profileMb: Int, allocatedMb: Int): Int {
            val raw = if (profileMb > 0) profileMb else allocatedMb
            return if (raw < 768) 1024 else raw
        }

        /**
         * Selects the appropriate Java Runtime.
         * Priority: Profile Setup -> Managed Java ([IJavaManager]) -> System Java.
         *
         * Pulled into the companion (rather than instance method) so tests can
         * exercise the full priority cascade with a fake [IJavaManager] without
         * having to construct the rest of [LauncherService]'s collaborators.
         */
        internal suspend fun resolveJavaPath(
            javaManager: IJavaManager,
            profile: InstanceProfile,
            defaultPath: Path,
            version: String
        ): String {
            if (!profile.javaPath.isNullOrEmpty()) return profile.javaPath!!
            runCatching {
                val managedPath = javaManager.getJavaPath(version)
                if (Files.exists(managedPath)) return managedPath.toString()
            }
            if (Files.exists(defaultPath)) return defaultPath.toString()
            return "java"
        }
    }
}
