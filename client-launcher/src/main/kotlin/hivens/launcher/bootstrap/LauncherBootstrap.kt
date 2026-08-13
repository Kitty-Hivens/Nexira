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
 * data-dir migration, session-id tagging, crash-report handler,
 * single-instance lock, Koin startup.
 *
 * The pipeline is split in two so the GUI can show a window BEFORE the
 * heavy boot work runs (the boot-threshold screen):
 *
 *   - [preWindow]  -- the synchronous, milliseconds-cheap prologue that MUST
 *     precede any window/toolkit init: log-dir property, session tagging,
 *     the single-instance gate (so a duplicate launch never flashes a
 *     window before yielding to the running instance).
 *   - [completeCore] + [finishBoot] -- the potentially-slow remainder
 *     (data-dir move, migration detect, Koin). The
 *     GUI runs these on a background thread behind a live window and maps
 *     the [Phase] callbacks onto its progress bar; the CLI runs them
 *     inline.
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
 * runs [preWindow], layers its toolkit setup + Swing crash dialog on
 * top, then drives [completeCore] + [finishBoot] from its boot thread.
 * Keeping the GUI edge out of this module keeps AWT out of the CLI's
 * reachable graph and out of the native image -- which is the whole
 * point, since AWT/Skiko is what blocks native-image of the Compose GUI.
 *
 * Anything that should run AFTER Koin but before the Compose entry
 * (puppet server startup, UI Koin module registration) is the caller's
 * responsibility -- those have client-ui dependencies and cannot live
 * here without inverting the module direction.
 */
object LauncherBootstrap {

    /**
     * Coarse boot phases, reported through the `onPhase` callbacks so a
     * boot-progress surface can label its bar honestly. Values arrive in
     * declaration order; a phase is reported when its work STARTS.
     */
    enum class Phase { Data, Network, Migration, Modules }

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

    /** Pre-window prologue values [completeCore] resumes from. */
    data class PreWindow(
        val initialPaths: PlatformPaths,
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
        val pre = preWindow(singleInstance = false)
        val core = completeCore(pre, relockOnMove = false)
        installHeadlessCrashHandler(core.crashReporter)
        return finishBoot(core, extraModules)
    }

    /**
     * The synchronous prologue: log-dir resolution, session tagging, and
     * (for the GUI) the single-instance gate. Everything here is
     * milliseconds-cheap by construction -- the point of the split is
     * that a window can go up immediately after this returns.
     *
     * Resolve logs dir BEFORE any LoggerFactory.getLogger() call so
     * logback.xml (which reads `${nexira.logs.dir}` for its rolling-file
     * appenders) sees the platform-correct path on its very first init.
     * PlatformPaths.system() is pure computation -- no logger init --
     * safe to call before the property is set.
     *
     * The resolved paths are an INITIAL value: [completeCore] may commit
     * a pending `data-dir` move and re-resolve; the post-move paths are
     * what the rest of the session runs on. The single-instance lock is
     * taken on the initial dir (a duplicate launch must be gated before
     * its window shows); [completeCore] re-locks on the post-move dir in
     * the rare pending-move case.
     */
    fun preWindow(singleInstance: Boolean): PreWindow {
        val initialPaths = PlatformPaths.system()
        System.setProperty("nexira.logs.dir", initialPaths.logsDir.toString())

        // Pulse: tag every log line in this process with a stable 8-char sessionId
        // so a multi-launch user dump can be sliced per process invocation
        // (`grep sessionId=abc12345 *.log`). System property (not MDC) because
        // MDC is thread-local, and we want this on every line from every thread --
        // the logback pattern reads the property via `${nexira.sessionId}`.
        // Set BEFORE the first possible logger init (the lock below logs).
        val sessionId = UUID.randomUUID().toString().take(8)
        System.setProperty("nexira.sessionId", sessionId)

        // Beacon: the very first entry in the action ring -- handy when reading a
        // bundle to confirm what process / version / OS produced it.
        ActionRing.record(
            "Launcher started (v${Branding.VERSION}, sessionId=$sessionId, os=${System.getProperty("os.name")})"
        )

        if (singleInstance) {
            // The data dir may not exist yet on a fresh install; the lock file
            // needs its parent. Idempotent, and completeCore re-creates after
            // a potential data-dir move.
            runCatching { Files.createDirectories(initialPaths.dataDir) }
            // Acquired BEFORE any window shows: a duplicate launch drops the
            // .show signal for the running instance and exits without ever
            // flashing a window of its own. Also before migration is consulted
            // -- two launchers started close together would otherwise both
            // render the MigrationScreen and race on file copies.
            if (!SingleInstance.acquire(initialPaths.dataDir)) exitProcess(0)
        }

        return PreWindow(initialPaths)
    }

    /**
     * The potentially-slow, AWT-free middle: pending data-dir move,
     * post-move re-resolution, data-dir creation, CrashReporter
     * construction. Safe to run on a background thread behind a live window.
     */
    fun completeCore(
        pre: PreWindow,
        relockOnMove: Boolean,
        onPhase: (Phase) -> Unit = {},
    ): Core {
        onPhase(Phase.Data)

        // Apply any pending data-dir move scheduled from the Settings UI. If
        // user clicked "Move data directory" -> picker -> restart, this is
        // where the relocation actually happens. Operation is idempotent;
        // safe to call on every startup.
        //
        // Edge case: if applyPending DOES move the data dir, the logs
        // property points at the old location and logback opens the file
        // there -- log entries about the move itself stream to the old path
        // right up until the source dir is deleted. Logback's rolling-file
        // appender keeps writing to the old (now-deleted on Linux /
        // unlinked-but-handle-held on Windows) file for the rest of this
        // session; the next launch starts fresh under the new path.
        DataDirMover.applyPending()

        // Re-resolve so the rest of boot uses the post-move data-dir.
        // No-op when applyPending didn't change anything (steady state).
        // Holding only the initial paths across applyPending was a race:
        // the move would copy + commit, then the rest of boot used the
        // stale captured path -- recreating an empty old dir and wiring
        // every singleton against it.
        val paths = PlatformPaths.system()
        if (paths.dataDir != pre.initialPaths.dataDir) {
            System.setProperty("nexira.logs.dir", paths.logsDir.toString())
            if (relockOnMove) {
                // The pre-window lock guards the OLD dir; a duplicate started
                // after the move would gate on the NEW dir. Best-effort
                // re-acquire keeps the single-instance invariant across the
                // (rare) move session; both locks are held until exit.
                if (!SingleInstance.acquire(paths.dataDir)) exitProcess(0)
            }
        }

        onPhase(Phase.Network)

        Files.createDirectories(paths.dataDir)

        // Constructed here (pre-Koin) so uncaught-exception handlers can
        // capture an instance with the resolved PlatformPaths. A parallel
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
     * Shared tail: migration detection, Koin startup. AWT-free. The
     * single-instance gate lives in [preWindow] now -- it must precede
     * window creation, and it already precedes migration by construction.
     */
    fun finishBoot(
        core: Core,
        extraModules: List<Module>,
        onPhase: (Phase) -> Unit = {},
    ): Result {
        val (paths, crashReporter) = core

        onPhase(Phase.Migration)

        // Migration runs INSIDE Compose, as a mandatory full-screen UI
        // shown before AppRoot. The detection is read here once so the
        // result is stable across recompositions; the actual copy and
        // progress reporting happens in MigrationScreen.
        val pendingMigration = DataDirMigration.detect(paths)

        onPhase(Phase.Modules)

        // Two createdAtStart hooks registered in appModule fire here:
        //   - SettingsRestoreHook       -- replays persisted experimental overrides.
        //   - AppCoroutineScopeHook     -- installs JVM shutdown hook that cancels
        //                                   the shared process-lifetime scope.
        // The shared CoroutineScope itself is also createdAtStart so the hook above
        // has a real instance to wire up; LauncherController and every fire-and-forget
        // launch/sync coroutine share it, so JVM shutdown cancels them together instead
        // of leaving a launched game process orphaned past the shutdown signal.
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
