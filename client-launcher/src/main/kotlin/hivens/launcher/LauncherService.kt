package hivens.launcher

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.model.ServerProfile
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileManifest
import hivens.core.data.HeapProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.jvm.HeapDeriver
import hivens.core.jvm.SystemMemory
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
    private val profilerStore: ProfilerProfileStore,
    private val agentExtractor: AgentExtractor,
    private val sharedAssetsDir: Path,
    private val sharedLibrariesDir: Path,
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
        adaptiveEnabled: Boolean,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val profile: InstanceProfile = profileManager.getProfile(serverProfile.assetDir)
        val version = serverProfile.version

        // 1. Memory allocation strategy (adaptive heap unless this instance is pinned)
        val adaptive = resolveAdaptive(
            enabled = adaptiveApplies(adaptiveEnabled, profile.fixedMemory),
            instanceDir = clientRootPath,
            baseMemoryMb = normalizeMemory(profile.memoryMb, allocatedMemoryMB),
        )
        val memory = adaptive.memoryMb

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
            classpath,
            agentJarPath = adaptive.agentJar,
            metricsOutPath = adaptive.metricsOut,
        )

        return spawnProcess(command, clientRootPath, onLog)
    }

    override suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process {
        return launchClientWithLogs(
            sessionData, serverProfile, clientRootPath, javaExecutablePath, allocatedMemoryMB,
            adaptiveEnabled = false,
        ) { _, _ -> /* Logs are ignored */ }
    }

    @Throws(IOException::class)
    override suspend fun launchPackClient(
        sessionData: SessionData,
        manifest: CachedManifestSnapshot,
        runtime: InstanceRuntime,
        clientRootPath: Path,
        javaPathOverride: Path?,
        allocatedMemoryMB: Int,
        adaptiveEnabled: Boolean,
        displayName: String,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val mcVersion = manifest.minecraftVersion

        // 1. Memory allocation strategy -- same floor logic as the SC path; the
        // InstanceRuntime value wins when positive, then adaptive may refine it
        // unless the instance is pinned.
        val adaptive = resolveAdaptive(
            enabled = adaptiveApplies(adaptiveEnabled, runtime.fixedMemory),
            instanceDir = clientRootPath,
            baseMemoryMb = normalizeMemory(runtime.memoryMb, allocatedMemoryMB),
        )
        val memory = adaptive.memoryMb

        onLog("Running $displayName...", LauncherLogType.INFO)

        // 2. Canonical runtime: vanilla + loader libraries + client + assets into
        // the SHARED roots (idempotent). Resolved FIRST so the loader-declared
        // Java major can drive JDK provisioning -- same MC version on a different
        // loader needs a different JDK (Cleanroom-1.12.2 wants 25, not 8).
        val nativesDir = commandBuilder.packNativesDir(mcVersion)
        val resolved = runtimeProvisioner.ensureRuntime(
            mcVersion = mcVersion,
            loaderName = manifest.loaderName,
            loaderVersion = manifest.loaderVersion,
        ) { current, total, file -> onLog("Runtime $current/$total: $file", LauncherLogType.INFO) }

        // 3. Java. Major precedence: loader-resolved override -> the pack manifest's
        // own declaration (authoritative for the pack) -> Mojang's per-version field
        // (captured into the resolved runtime). The heuristic disappears here: the
        // pack ALWAYS declares its major in the manifest. Path precedence: instance
        // pin (runtime.javaPath) > caller override (javaPathOverride) > managed for
        // the declared major. Skip provisioning a managed JDK we would discard --
        // when the instance pins its own java, don't trigger the ~200 MB download.
        val javaMajor = resolved.javaMajor ?: manifest.javaMajor
        val javaExec: String = if (!runtime.javaPath.isNullOrEmpty()) {
            runtime.javaPath!!
        } else {
            val defaultJava = javaPathOverride ?: javaManager.getJavaPathForMajor(javaMajor) { msg ->
                onLog(msg, LauncherLogType.INFO)
            }
            resolvePackJavaPath(runtime, defaultJava)
        }

        log.info("Session initialization (pack): {}, Java: {} (major {}), Heap: {}MB", displayName, javaExec, javaMajor, memory)

        // 4. Natives stay per-instance, but are now extracted from the jars the
        // provisioner resolved from the manifest -- so the LWJGL version matches
        // the classpath for ANY MC version, not just the few the SC path hardcodes.
        // Assets are the shared root the provisioner just populated.
        envPreparer.prepareNativesFromManifest(clientRootPath, nativesDir, resolved.natives)

        // 5. Profile-driven command: main class / classpath / args come from the
        // resolved runtime; assets point at the shared root.
        val command = commandBuilder.buildPackCommand(
            javaExec = javaExec,
            memoryMB = memory,
            gameDir = clientRootPath,
            sharedAssetsDir = sharedAssetsDir,
            sharedLibrariesDir = sharedLibrariesDir,
            nativesDirName = nativesDir,
            versionLabel = packVersionLabel(manifest.loaderName, mcVersion),
            javaMajor = javaMajor,
            runtime = resolved,
            session = sessionData,
            jvmArgsOverride = runtime.jvmArgs,
            agentJarPath = adaptive.agentJar,
            metricsOutPath = adaptive.metricsOut,
        )

        return spawnProcess(command, clientRootPath, onLog)
    }

    /**
     * Builds, starts, and log-attaches the game process. Both launch paths
     * (SC server + pack) run on the caller's IO dispatcher, so the blocking
     * ProcessBuilder.start happens on IO without an extra context switch.
     */
    private fun spawnProcess(
        command: List<String>,
        clientRootPath: Path,
        onLog: (String, LauncherLogType) -> Unit,
    ): Process {
        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)
        onLog("CMD: ${java.lang.String.join(" ", command)}", LauncherLogType.INFO)
        val process = pb.start()
        logHandler.attach(process, onLog)
        return process
    }

    /**
     * Resolves heap + profiler-agent attachment for a launch. Adaptive off ->
     * static [baseMemoryMb], no agent. Adaptive on -> fold the previous session's
     * metrics into the per-instance rolling profile, derive the next heap from the
     * samples (live set when reliable, else the observed peak; keep [baseMemoryMb]
     * until data exists), persist, and attach
     * the agent so THIS session produces the next sample.
     */
    private fun resolveAdaptive(enabled: Boolean, instanceDir: Path, baseMemoryMb: Int): AdaptiveLaunch {
        if (!enabled) return AdaptiveLaunch(baseMemoryMb, null, null)

        val profile = profilerStore.readProfile(instanceDir) ?: HeapProfile()
        // Consume the previous session's metrics: fold once, never re-read a stale
        // file (a session that crashed before its shutdown hook wrote leaves none).
        val last = profilerStore.readMetrics(instanceDir)
        profilerStore.deleteMetrics(instanceDir)
        // Roll the previous session into the rolling window. foldSample drops zero-signal
        // records (no GC AND peak 0) so a run of them can't evict good samples and
        // collapse the heap back to the static base; reliability is filtered per-term in
        // the deriver.
        val samples = HeapDeriver.foldSample(profile.recentSamples, last, ProfilerProfileStore.SAMPLE_WINDOW)

        // 1024 == the modded-client floor normalizeMemory also enforces.
        val derived = HeapDeriver.derive(samples, SystemMemory.totalPhysicalMb(), floorMb = 1024)
        if (samples != profile.recentSamples || derived != profile.derivedHeapMb) {
            profilerStore.writeProfile(
                instanceDir,
                profile.copy(
                    derivedHeapMb = derived,
                    recentSamples = samples,
                    updatedAtEpoch = System.currentTimeMillis(),
                ),
            )
        }

        val agentJar = agentExtractor.ensureExtracted()
        val metricsOut = if (agentJar != null) profilerStore.metricsPath(instanceDir) else null
        return AdaptiveLaunch(derived ?: baseMemoryMb, agentJar, metricsOut)
    }

    private data class AdaptiveLaunch(val memoryMb: Int, val agentJar: Path?, val metricsOut: Path?)

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
         * Whether the adaptive heap sizer applies to an instance: the global signal
         * [adaptiveEnabled] (experimental master AND the adaptive toggle) must be on
         * AND the instance must not be pinned to a fixed heap.
         */
        internal fun adaptiveApplies(adaptiveEnabled: Boolean, fixedMemory: Boolean): Boolean =
            adaptiveEnabled && !fixedMemory

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
