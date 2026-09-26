package hivens.launcher

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.HeapProfile
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.RuntimePrefs
import hivens.core.data.SessionData
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.HeapDeriver
import hivens.core.jvm.SystemMemory
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.SpawnResult
import hivens.launcher.component.EarlyLoadingScreen
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.launch.PackPrepBlocked
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.security.JavaBinary
import hivens.launcher.security.LaunchEnvironment
import hivens.launcher.smrt.SmrtAuthlibSwapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Implementation of the Minecraft client launch service.
 *
 * Acts as a facade, coordinating the work of [EnvironmentPreparer] (natives),
 * [RuntimeProvisioner] (the loader-resolved runtime), [GameCommandBuilder] (the
 * JVM command) and [ProcessLogHandler] (stdout/stderr interception). All
 * collaborators are supplied via constructor injection so that this service can
 * be unit-tested in isolation.
 */
internal class LauncherService(
    private val javaManager: IJavaManager,
    private val envPreparer: EnvironmentPreparer,
    private val commandBuilder: GameCommandBuilder,
    private val logHandler: ProcessLogHandler,
    private val runtimeProvisioner: RuntimeProvisioner,
    private val profilerStore: ProfilerProfileStore,
    private val agentExtractor: AgentExtractor,
    private val authlibSwapper: SmrtAuthlibSwapper,
    private val sharedAssetsDir: Path,
    private val sharedLibrariesDir: Path,
) : ILauncherService {

    private val log = LoggerFactory.getLogger(LauncherService::class.java)

    override suspend fun launchPackClient(
        sessionData: SessionData,
        manifest: CachedManifestSnapshot,
        runtime: InstanceRuntime,
        clientRootPath: Path,
        javaPathOverride: Path?,
        adaptiveEnabled: Boolean,
        redirectAuthHost: Boolean,
        useNetworkAgent: Boolean,
        useSmartycraftAuthLib: Boolean,
        boundLaunch: Boolean,
        seal: (suspend () -> Boolean)?,
        displayName: String,
        onLog: (String, LauncherLogType) -> Unit
    ): SpawnResult = try {
        val mcVersion = manifest.minecraftVersion
        val scBound = manifest.authRequirement?.scServerId != null

        // 1. Heap: pinned -> explicit value, else the machine-aware Automatic
        // baseline that the adaptive sizer refines from.
        val adaptive = resolveAdaptive(
            enabled = adaptiveApplies(adaptiveEnabled, runtime.fixedMemory),
            instanceDir = clientRootPath,
            baseMemoryMb = baselineMemory(runtime.fixedMemory, runtime.memoryMb, SystemMemory.totalPhysicalMb()),
        )
        val memory = adaptive.memoryMb

        onLog("Running $displayName...", LauncherLogType.INFO)

        // 2. Canonical runtime: vanilla + loader libraries + client + assets into
        // the SHARED roots (idempotent). Resolved FIRST so the loader-declared
        // Java major can drive JDK provisioning -- same MC version on a different
        // loader needs a different JDK (Cleanroom-1.12.2 wants 25, not 8).
        val nativesDir = commandBuilder.packNativesDir(mcVersion)
        val baseRuntime = runtimeProvisioner.ensureRuntime(
            mcVersion = mcVersion,
            loaderName = manifest.loaderName,
            loaderVersion = manifest.loaderVersion,
        ) { current, total, file -> onLog("Runtime $current/$total: $file", LauncherLogType.INFO) }

        // 2b. SC binding: an SC-bound pack provisions the VANILLA authlib (sends the
        // join to Mojang -> 403 for an SC token). Two mechanisms steer it back to
        // SC: the authlib-redirect agent (default, attached at step 5 below) and
        // SC's patched authlib jar (opt-in fallback, swapped onto the classpath
        // here). No-op for Hivens-native packs. The pack's own mods (open-smrt
        // interop included) come from the sync; nothing is injected here.
        val resolved = applySmrtBinding(
            manifest, sessionData, mcVersion, baseRuntime,
            swapAuthlib = useSmartycraftAuthLib, onLog = onLog,
        )

        // An SC-bound join needs at least one mechanism; with neither, the vanilla
        // authlib hits Mojang and the server rejects the session. Surface it rather
        // than spawn a guaranteed-to-fail join silently.
        if (scBound && !useNetworkAgent && !useSmartycraftAuthLib) {
            onLog(
                "Neither the network agent nor the SmartyCraft authlib is enabled; the SC join will be rejected",
                LauncherLogType.WARN,
            )
        }
        val authlibAgent = if (scBound && useNetworkAgent) agentExtractor.ensureAuthlibAgent() else null

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

        // A launch that will carry a token runs the interpreter it was given, and
        // that interpreter decides everything the command line just decided. A
        // wrapper script in its place makes all of it someone else's choice.
        if (boundLaunch && !JavaBinary.isNativeExecutable(Path.of(javaExec))) {
            log.error("Refusing a bound launch: {} is not a native executable", javaExec)
            onLog("The Java runtime at $javaExec is not a program -- refusing to launch", LauncherLogType.ERROR)
            throw PackPrepBlocked(LaunchError.Internal("java-not-executable"))
        }

        // 4. Natives stay per-instance, extracted from the jars the provisioner
        // resolved from the manifest -- so the LWJGL version matches the classpath
        // for any MC version. Assets are the shared root the provisioner just
        // populated.
        envPreparer.prepareNativesFromManifest(clientRootPath, nativesDir, resolved.natives, rebuild = boundLaunch)

        // 4b. FML's loading screen. A config that could not be written leaves the
        // screen as the pack had it, which is a risk to this launch on Wayland but
        // no reason to refuse it.
        val earlyScreen = EarlyLoadingScreen.enforced(runtime.earlyLoadingScreen)
        if (earlyScreen != null && EarlyLoadingScreen.configurableIn(resolved)) {
            runCatching { EarlyLoadingScreen.writeConfig(clientRootPath, earlyScreen) }
                .onSuccess { changed ->
                    if (changed) onLog("Loader loading screen set to ${if (earlyScreen) "on" else "off"} in config/fml.toml", LauncherLogType.INFO)
                }
                .onFailure {
                    log.warn("Could not set the loader loading screen for {}", displayName, it)
                    onLog("Could not write config/fml.toml: ${it.message}", LauncherLogType.WARN)
                }
        }

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
            redirectAuthHost = redirectAuthHost,
            restrictJvmArgs = boundLaunch,
            agentJarPath = adaptive.agentJar,
            metricsOutPath = adaptive.metricsOut,
            authlibAgentJarPath = authlibAgent,
            windowWidth = runtime.windowWidth.takeIf { runtime.windowSizeOverride },
            windowHeight = runtime.windowHeight.takeIf { runtime.windowSizeOverride },
            fullScreen = runtime.fullScreen,
            earlyLoadingScreen = earlyScreen,
        )

        // Last statement before the process exists: everything is provisioned,
        // the command is built, and nothing else stands between here and the
        // game reading mods/.
        if (seal != null && !seal()) {
            log.error("Refusing to spawn {}: the instance no longer matches the pack", displayName)
            throw PackPrepBlocked(LaunchError.ContentChangedDuringLaunch)
        }
        SpawnResult.Started(
            ProcessLaunchHandle(spawnProcess(command, clientRootPath, boundLaunch, onLog)),
            resolvedLoaderVersion = resolved.loaderVersion,
        )
    } catch (e: PackPrepBlocked) {
        // SC-binding step could not complete; surface the carried reason.
        SpawnResult.Failed(e.error)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("Pack launch failed for {}", displayName, e)
        SpawnResult.Failed(LaunchError.Internal(e.message ?: ""))
    }

    /**
     * Applies SC-binding to a freshly provisioned pack [runtime]: returns it
     * unchanged for a non-SC pack or when [swapAuthlib] is off; otherwise
     * repoints the vanilla authlib classpath entry to SC's patched jar.
     *
     * The authlib swap is the OPT-IN fallback to the authlib-redirect agent (the
     * default mechanism, wired in [launchPackClient] / [GameCommandBuilder]); it
     * does not run unless the user enabled it. When it does run it throws
     * [PackPrepBlocked] (mapped to a [LaunchError] by the controller) if the
     * patched authlib cannot be sourced -- with the swap selected, vanilla authlib
     * is a guaranteed rejection.
     *
     * No mods are touched here. A pack carries its own mods (the open-smrt-network
     * interop included) and mod content is the sync's job, scoped to the manifest;
     * injecting a helper on top would duplicate the coremod the pack already ships.
     *
     * The patched authlib comes from the SC session's own file manifest
     * ([SessionData.fileManifest], populated by the pre-spawn re-auth), so it is
     * pulled from SC's own client distribution and nothing of SC's is rehosted. Only the resolved classpath entry is rewritten; the
     * shared `libraries/` root stays vanilla (a patched jar there would hit every
     * pack of that MC version and be reverted by the provisioner's size check).
     */
    private suspend fun applySmrtBinding(
        manifest: CachedManifestSnapshot,
        sessionData: SessionData,
        mcVersion: String,
        runtime: ResolvedRuntime,
        swapAuthlib: Boolean,
        onLog: (String, LauncherLogType) -> Unit,
    ): ResolvedRuntime {
        // SC binding covers SmartyCraft and the SC half of Both -- both expose a
        // non-null scServerId; Microsoft-only (null) needs no SC authlib.
        val scServerId = manifest.authRequirement?.scServerId ?: return runtime

        // authlib swap: opt-in fallback to the redirect agent. When selected the
        // patched jar is mandatory (vanilla authlib is a guaranteed 403).
        if (!swapAuthlib) return runtime
        val authlib = findAuthlibLibrary(runtime)
            ?: throw PackPrepBlocked(LaunchError.AuthlibUnavailable(mcVersion))
        val patched = authlibSwapper.ensurePatchedAuthlib(scServerId, sessionData.fileManifest)
            ?: throw PackPrepBlocked(LaunchError.AuthlibUnavailable(mcVersion))
        onLog("Using SmartyCraft authlib for $scServerId", LauncherLogType.INFO)
        return swapAuthlibPath(runtime, authlib, patched)
    }

    /**
     * Builds, starts, and log-attaches the game process. The launch runs on the
     * caller's IO dispatcher, so the blocking ProcessBuilder.start happens on IO
     * without an extra context switch.
     */
    private fun spawnProcess(
        command: List<String>,
        clientRootPath: Path,
        boundLaunch: Boolean,
        onLog: (String, LauncherLogType) -> Unit,
    ): Process {
        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)
        // The game inherits this process's environment, which inherited the
        // session's, so a value in a shell profile reaches every launch. Named in
        // the log rather than dropped quietly: a user who set one deliberately is
        // owed the reason their tool stopped attaching.
        LaunchEnvironment.seal(pb.environment(), boundLaunch).forEach {
            onLog("Sealed $it out of the game environment", LauncherLogType.INFO)
        }
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
        val derived = HeapDeriver.derive(
            samples, SystemMemory.totalPhysicalMb(), floorMb = 1024, current = profile.derivedHeapMb,
        )
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

        val agentJar = agentExtractor.ensureProfilerAgent()
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
        /** The vanilla `com.mojang:authlib` classpath entry in [runtime], or null if absent. */
        internal fun findAuthlibLibrary(runtime: ResolvedRuntime): ResolvedLibrary? =
            runtime.libraries.firstOrNull { it.coord.group == "com.mojang" && it.coord.artifact == "authlib" }

        /** [runtime] with [target]'s path repointed to [newPath]; every other entry left as-is. */
        internal fun swapAuthlibPath(runtime: ResolvedRuntime, target: ResolvedLibrary, newPath: Path): ResolvedRuntime =
            runtime.copy(libraries = runtime.libraries.map { if (it === target) it.copy(path = newPath) else it })

        /**
         * A pinned heap as the launch will use it: anything below 768 MB is bumped
         * to 1024 MB, which is the floor a modded client needs to be viable at all.
         */
        internal fun normalizeMemory(profileMb: Int): Int = if (profileMb < 768) 1024 else profileMb

        /**
         * Whether the adaptive heap sizer applies to an instance: the global signal
         * [adaptiveEnabled] (experimental master AND the adaptive toggle) must be on
         * AND the instance must not be pinned to a fixed heap.
         */
        internal fun adaptiveApplies(adaptiveEnabled: Boolean, fixedMemory: Boolean): Boolean =
            adaptiveEnabled && !fixedMemory

        /**
         * The baseline heap before any adaptive refinement. A pinned instance
         * ([fixedMemory]) keeps its explicit [profileMb] (respected as-is, even above
         * the machine ceiling -- a deliberate value is the user's call); everything
         * else uses the machine-aware [AutomaticHeap] baseline, which is also the
         * cold-start the adaptive sizer grows from. Pure.
         *
         * "Everything else" includes an instance flagged as pinned that names no
         * heap. That combination is not reachable from the UI -- pinning happens by
         * choosing a number, which is what writes one -- so a record carrying it has
         * been edited by hand, and the machine baseline is a better answer for it
         * than a stored constant was.
         */
        internal fun baselineMemory(
            fixedMemory: Boolean,
            profileMb: Int,
            systemRamMb: Int,
        ): Int = if (fixedMemory && profileMb > 0) normalizeMemory(profileMb)
                 else AutomaticHeap.compute(systemRamMb)

        /**
         * Java path resolution: [RuntimePrefs.javaPath] wins, and without it
         * the caller's pre-resolved [defaultPath] does -- the managed-Java step
         * has already been taken by then, against the pack's declared major.
         */
        internal fun resolvePackJavaPath(
            runtime: RuntimePrefs,
            defaultPath: Path,
        ): String {
            val explicit = runtime.javaPath
            if (!explicit.isNullOrEmpty()) return explicit
            if (Files.exists(defaultPath)) return defaultPath.toString()
            return "java"
        }
    }
}

/**
 * Wraps the spawned [Process] so the core SPI hands back a [LaunchHandle]
 * instead of the JVM type. [awaitExit] blocks the calling dispatcher (the
 * launcher's IO launch coroutine), mirroring the prior in-coroutine
 * `process.waitFor()` -- cancelling the launch job does not interrupt it, so
 * the orchestrator sends [terminate] first to let the wait return.
 */
private class ProcessLaunchHandle(private val process: Process) : LaunchHandle {
    // On IO by its own doing rather than by the caller's promise: the wait is
    // unbounded, and a blocking wait that borrows whatever thread it was called on
    // is one refactor away from parking a dispatcher that had other work.
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    /**
     * SIGTERM, then SIGKILL if the game did not take the hint.
     *
     * SIGTERM is handled by a JVM shutdown hook, so a healthy game runs it, saves
     * and exits -- which is why the polite signal goes first. A wedged JVM never
     * reaches the hook, and re-sending the signal changes nothing there: it is
     * already pending, and the kernel does not make a delivered signal more
     * insistent by repetition. Only [Process.destroyForcibly] ends that, because
     * nothing in user space gets a say in it.
     *
     * The escalation runs on its own thread. [terminate] is called from
     * `LauncherController.abort`, which is a plain function invoked from a Compose
     * click handler -- blocking there would freeze the window on exactly the
     * process that is refusing to die.
     *
     * Descendants are taken first: killing the parent orphans them, and on Windows
     * `destroyForcibly` does not reach them at all.
     */
    override fun terminate() {
        runCatching { process.destroy() }
        Thread {
            val exited = runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!exited) {
                runCatching { process.descendants().forEach { child -> child.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
            }
        }.apply {
            isDaemon = true
            name = "game-terminate-escalation"
        }.start()
    }

    override val stdin: OutputStream get() = process.outputStream

    private companion object {
        /** Long enough for a modded client to run its shutdown hook and save. */
        const val TERMINATE_GRACE_SECONDS = 8L
    }
}
