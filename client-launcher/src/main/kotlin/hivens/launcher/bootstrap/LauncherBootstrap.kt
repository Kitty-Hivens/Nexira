package hivens.launcher.bootstrap

import hivens.config.Branding
import hivens.core.diag.ActionRing
import hivens.launcher.CrashReporter
import hivens.launcher.di.appModule
import hivens.launcher.di.authModule
import hivens.launcher.di.cacheModule
import hivens.launcher.di.launchPipelineModule
import hivens.launcher.di.mirrorModule
import hivens.launcher.di.networkModule
import hivens.launcher.di.runtimeModule
import hivens.launcher.di.updateModule
import hivens.launcher.network.NetworkState
import hivens.launcher.platform.DataDirMigration
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.platform.SingleInstance
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Everything that has to happen before an entrypoint takes over the JVM:
 * log directory resolution so logback.xml's `${nexira.logs.dir}`
 * substitution works on the very first LoggerFactory call, pending
 * data-dir migration, session-id tagging, NetworkState SSL-bypass
 * restore, crash-report handler, single-instance lock, Koin startup.
 *
 * Result is the small set of values the entrypoint needs to keep
 * threading through: the resolved [PlatformPaths] (for window-side
 * `.show` watcher and data-dir-relative reads) and a possibly non-null
 * [DataDirMigration.Source] that gates whether the first composition
 * shows MigrationScreen or AppRoot.
 *
 * This module is AWT-free by construction. Entry pipelines COMPOSE the
 * public pieces: [preBootHeadless] is the CLI's whole pipeline (a
 * log-and-persist crash handler, no single-instance lock);
 * the GUI pipeline lives with the UI layer (hivens.ui.bootstrap), which
 * runs [prepareCore], layers its toolkit setup + Swing crash dialog on
 * top, then calls [finishBoot]. Keeping the GUI edge out of this module
 * keeps AWT out of the CLI's reachable graph and out of the native
 * image -- which is the whole point, since AWT/Skiko is what blocks
 * native-image of the Compose GUI.
 *
 * Anything that should run AFTER Koin but before the Compose entry
 * (puppet server startup, UI Koin module registration) is the caller's
 * responsibility -- those have client-ui dependencies and cannot live
 * here without inverting the module direction.
 */
object LauncherBootstrap {

    /**
     * Bundle of values the Compose layer needs from pre-Compose setup.
     *
     * [crashReporter] is exposed for parity with the Koin singleton --
     * post-Koin consumers grab their own instance via injection. The
     * shared mutable state between the two instances is zero, so the
     * parallel construction is functionally identical.
     */
    data class Result(
        val paths: PlatformPaths,
        val pendingMigration: DataDirMigration.Source?,
        val crashReporter: CrashReporter,
    )

    /** Pre-Koin core values an entry pipeline composes [finishBoot] around. */
    data class Core(
        val paths: PlatformPaths,
        val crashReporter: CrashReporter,
    )

    /**
     * Headless variant for the CLI / native-image entrypoint: identical
     * logging, paths, migration and Koin setup, but touches no AWT/Swing.
     * It installs a log-and-persist crash handler (no Swing dialog) and
     * skips the single-instance lock so the CLI can run alongside a GUI
     * instance instead of silently exiting when the GUI holds the lock.
     */
    fun preBootHeadless(extraModules: List<Module> = emptyList()): Result {
        val core = prepareCore()
        installHeadlessCrashHandler(core.crashReporter)
        return finishBoot(core, extraModules, singleInstance = false)
    }

    /**
     * AWT-free core: log dir + data-dir migration + session tagging +
     * NetworkState restore + data dir + CrashReporter. Shared verbatim by
     * every entrypoint; contains zero references to AWT/Swing so the CLI's
     * reachable graph stays clean. Public for the GUI pipeline in
     * hivens.ui.bootstrap, which layers toolkit setup + its Swing crash
     * handler between this and [finishBoot].
     */
    fun prepareCore(): Core {
        // Resolve logs dir BEFORE any LoggerFactory.getLogger() call so
        // logback.xml (which reads `${nexira.logs.dir}` for its rolling-file
        // appenders) sees the platform-correct path on its very first init.
        // PlatformPaths.system() is pure computation -- no logger init --
        // safe to call before the property is set. DataDirMover and
        // BootstrapConf both have lazy log fields specifically so this
        // ordering works without their applyPending() / read() touching
        // logback first.
        //
        // The initial resolution is a temporary value: applyPending below
        // can commit a brand-new `data-dir` into BootstrapConf, in which
        // case the second PlatformPaths.system() call right after picks
        // up the new path and that becomes [paths] for the rest of the
        // session. Holding only [initialPaths] across applyPending was a
        // race: the move would copy + commit, then the rest of preBoot
        // (Files.createDirectories, NetworkState.initialize, Koin) used
        // the stale captured path -- recreating an empty old dir and
        // wiring every singleton against it. The user observed:
        // "files moved but the launcher still uses the old (now empty)
        // path", logged out, fresh-login surfaced trustAnchors / network
        // errors because the cache / creds / ssl-bypasses landed at the
        // wrong location. The re-resolve below is the fix.
        val initialPaths = PlatformPaths.system()
        System.setProperty("nexira.logs.dir", initialPaths.logsDir.toString())

        // NOW safe to apply any pending data-dir move scheduled from the
        // Settings UI. If user clicked "Move data directory" -> picker ->
        // restart, this is where the relocation actually happens. Operation
        // is idempotent; safe to call on every startup. The first log line
        // it produces (only in the actual-move case, no-op otherwise) lands
        // in `initialPaths.logsDir/launcher.log`.
        //
        // Edge case: if applyPending DOES move the data dir, initialPaths.logsDir
        // points at the old location and logback opens the file there --
        // log entries about the move itself stream to the old path right
        // up until the source dir is deleted. Logback's rolling-file
        // appender keeps writing to the old (now-deleted on Linux /
        // unlinked-but-handle-held on Windows) file for the rest of this
        // session; the next launch starts fresh under the new path.
        DataDirMover.applyPending()

        // Re-resolve so the rest of preBoot uses the post-move data-dir.
        // No-op when applyPending didn't change anything (steady state).
        val paths = PlatformPaths.system()
        if (paths.dataDir != initialPaths.dataDir) {
            System.setProperty("nexira.logs.dir", paths.logsDir.toString())
        }

        // Pulse: tag every log line in this process with a stable 8-char sessionId
        // so a multi-launch user dump can be sliced per process invocation
        // (`grep sessionId=abc12345 *.log`). System property (not MDC) because
        // MDC is thread-local, and we want this on every line from every thread --
        // the logback pattern reads the property via `${nexira.sessionId}`.
        val sessionId = UUID.randomUUID().toString().take(8)
        System.setProperty("nexira.sessionId", sessionId)

        // Beacon: the very first entry in the action ring -- handy when reading a
        // bundle to confirm what process / version / OS produced it.
        ActionRing.record(
            "Launcher started (v${Branding.VERSION}, sessionId=$sessionId, os=${System.getProperty("os.name")})"
        )

        // Vault #2: wire SSL-bypass persistence. Expired entries from prior
        // sessions are dropped during load -- a 30-day grant from a month ago
        // doesn't silently re-arm itself. Called before Koin / HttpClientProvider
        // bootstrap so the very first network request sees the correct bypass
        // state. (Calling later would race: HttpClientProvider's selector
        // reads `NetworkState.bypassFor(...)` and could see an empty set if
        // initialize hadn't run yet.)
        NetworkState.initialize(paths.dataDir.resolve("ssl-bypasses.json"))

        Files.createDirectories(paths.dataDir)

        // Constructed here (pre-Koin) so the uncaught-exception handler below
        // can capture an instance with the resolved PlatformPaths. A parallel
        // Koin singleton wires the same shape for post-Koin consumers; they
        // share no mutable state so the parallel instances are functionally
        // identical.
        val crashReporter = CrashReporter(paths)

        return Core(paths, crashReporter)
    }

    /**
     * Headless crash handler: logs and persists the report to disk, no
     * Swing dialog. The GUI pipeline installs its own dialog-showing
     * handler instead.
     */
    private fun installHeadlessCrashHandler(crashReporter: CrashReporter) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val handlerLog = LoggerFactory.getLogger("CrashHandler")
            handlerLog.error("Uncaught exception on thread '${thread.name}'", throwable)
            runCatching {
                val report     = crashReporter.generate(throwable, thread)
                val reportFile = crashReporter.saveToDisk(report)
                handlerLog.error("Crash report written to {}", reportFile.absolutePath)
            }
        }
    }

    /**
     * Shared tail: single-instance lock (GUI only), migration detection,
     * Koin startup. AWT-free. Public for the GUI pipeline in
     * hivens.ui.bootstrap.
     */
    fun finishBoot(
        core: Core,
        extraModules: List<Module>,
        singleInstance: Boolean,
    ): Result {
        val (paths, crashReporter) = core
        // Single-instance lock acquired BEFORE migration is consulted. Two
        // launchers started close together would otherwise both render the
        // MigrationScreen and race on file copies. DataDirMigration's
        // emptiness check is taught to ignore .lock / .show / .migrated so
        // its first-run trigger still fires. Skipped for the headless CLI --
        // it must coexist with a running GUI instance, not exit when the GUI
        // already holds the lock.
        if (singleInstance && !SingleInstance.acquire(paths.dataDir)) exitProcess(0)

        // Migration runs INSIDE Compose now, as a mandatory full-screen UI
        // shown before AppRoot. The detection is read here once so the
        // result is stable across recompositions; the actual copy and
        // progress reporting happens in MigrationScreen.
        val pendingMigration = DataDirMigration.detect(paths)

        // Two createdAtStart hooks registered in appModule fire here:
        //   - SettingsRestoreHook       -- replays persisted experimental overrides.
        //   - AppCoroutineScopeHook     -- installs JVM shutdown hook that cancels
        //                                   the shared process-lifetime scope.
        // The shared CoroutineScope itself is also createdAtStart so the hook above
        // has a real instance to wire up, and LauncherController + tray-launch flow
        // share the same scope -- otherwise a tray-launched process can outlive
        // the JVM shutdown signal because its launching coroutine isn't joined to
        // the canceled scope.
        startKoin {
            modules(
                listOf(
                    networkModule,
                    authModule,
                    cacheModule,
                    runtimeModule,
                    mirrorModule,
                    launchPipelineModule,
                    updateModule,
                    appModule,
                ) + extraModules,
            )
        }

        return Result(paths, pendingMigration, crashReporter)
    }
}
