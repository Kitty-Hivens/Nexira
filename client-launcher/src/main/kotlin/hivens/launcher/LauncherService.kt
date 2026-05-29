package hivens.launcher

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.model.ServerProfile
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.runtime.RuntimeProvisioner
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Implementation of the Minecraft client launch service.
 *
 * Acts as a facade, coordinating the work of [EnvironmentPreparer] (natives + assets),
 * [ClasspathProvider] (manifest -> classpath), [GameCommandBuilder] (version-specific JVM
 * command) and [ProcessLogHandler] (stdout/stderr interception). All collaborators are
 * supplied via constructor injection so that this service can be unit-tested in isolation.
 */
internal class LauncherService(
    private val profileManager: ProfileManager,
    private val javaManager: IJavaManager,
    private val envPreparer: EnvironmentPreparer,
    private val classpathProvider: ClasspathProvider,
    private val commandBuilder: GameCommandBuilder,
    private val logHandler: ProcessLogHandler,
    private val runtimeProvisioner: RuntimeProvisioner,
    private val sharedAssetsDir: Path,
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

    @Throws(IOException::class)
    override suspend fun launchPackClient(
        sessionData: SessionData,
        manifest: CachedManifestSnapshot,
        runtime: InstanceRuntime,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        displayName: String,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val mcVersion = manifest.minecraftVersion

        // 1. Memory allocation strategy -- same floor logic as the
        // SC path; the InstanceRuntime value wins when positive.
        val memory = normalizeMemory(runtime.memoryMb, allocatedMemoryMB)

        // 2. Java path. Pack-centric runtime carries an optional
        // explicit override; without it the caller's resolved default
        // wins (LauncherController already consulted JavaManager).
        val javaExec: String = resolvePackJavaPath(runtime, javaExecutablePath)

        log.info("Session initialization (pack): {}, Java: {}, Heap: {}MB", displayName, javaExec, memory)
        onLog("Running $displayName...", LauncherLogType.INFO)

        // 3. Canonical runtime: vanilla + loader libraries + client + assets into
        // the SHARED roots (idempotent). Throws clearly if it cannot provision --
        // no more "ready" followed by an empty-classpath crash.
        val nativesDir = commandBuilder.getNativesDir(mcVersion)
        val resolved = runtimeProvisioner.ensureRuntime(
            mcVersion = mcVersion,
            loaderName = manifest.loaderName,
            loaderVersion = manifest.loaderVersion,
        ) { current, total, file -> onLog("Runtime $current/$total: $file", LauncherLogType.INFO) }

        // 4. Natives stay per-instance (LWJGL via EnvironmentPreparer); assets are
        // the shared root the provisioner just populated.
        envPreparer.prepareNatives(clientRootPath, nativesDir, mcVersion)

        // 5. Profile-driven command: main class / classpath / args come from the
        // resolved runtime; assets point at the shared root.
        val command = commandBuilder.buildPackCommand(
            javaExec = javaExec,
            memoryMB = memory,
            gameDir = clientRootPath,
            sharedAssetsDir = sharedAssetsDir,
            nativesDirName = nativesDir,
            versionLabel = packVersionLabel(manifest.loaderName, mcVersion),
            runtime = resolved,
            session = sessionData,
            jvmArgsOverride = runtime.jvmArgs,
        )

        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)

        onLog("CMD: ${java.lang.String.join(" ", command)}", LauncherLogType.INFO)

        val process = pb.start()
        logHandler.attach(process, onLog)
        return process
    }

    /** Display label for `--version`, e.g. "Forge 1.12.2" / "Fabric 1.20.1". */
    private fun packVersionLabel(loaderName: String, mcVersion: String): String {
        val loader = loaderName.trim().ifEmpty { "Minecraft" }.replaceFirstChar { it.uppercaseChar() }
        return "$loader $mcVersion"
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
         * Pack-centric Java path resolution. Mirrors [resolveJavaPath]'s
         * fallback ladder but pulls the override from [InstanceRuntime]
         * instead of the legacy [InstanceProfile]. The runtime's
         * `javaPath` lands here as the highest priority; without it the
         * caller's pre-resolved [defaultPath] wins (LauncherController
         * already consulted JavaManager for the pack's Java major).
         */
        internal fun resolvePackJavaPath(
            runtime: InstanceRuntime,
            defaultPath: Path,
        ): String {
            val explicit = runtime.javaPath
            if (!explicit.isNullOrEmpty()) return explicit
            if (Files.exists(defaultPath)) return defaultPath.toString()
            return "java"
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
