package hivens.launcher.bootstrap

import hivens.config.Branding
import hivens.core.diag.ActionRing
import hivens.launcher.CrashReporter
import hivens.launcher.di.desktop.desktopModules
import hivens.launcher.di.launcherModules
import hivens.launcher.platform.DataDirMigration
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.platform.SingleInstance
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Everything that has to happen before an entrypoint takes over the JVM:
 * the log directory and session tag (the entry point names them first,
 * see [LogDirectory]), pending data-dir migration, crash-report handler,
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
 *     the [Phase] callbacks onto its progress bar.
 *
 * Result is the small set of values the entrypoint needs to keep
 * threading through: the resolved [PlatformPaths] (for window-side
 * `.show` watcher and data-dir-relative reads) and a possibly non-null
 * [DataDirMigration.Source] that gates whether the first composition
 * shows MigrationScreen or AppRoot.
 *
 * Koin is started as an isolated application and never registered in Koin's
 * global context. The entrypoint holds it ([Result.koin]) and hands it to what
 * needs it, the composition through host-provided locals. A module loaded from
 * a jar therefore finds no launcher graph behind Koin's global entry points.
 *
 * This module is AWT-free by construction. An entry pipeline composes the
 * public pieces: the GUI's lives with the UI layer (hivens.ui.bootstrap),
 * which runs [preWindow], layers its toolkit setup and Swing crash dialog
 * on top, then drives [completeCore] and [finishBoot] from its boot thread.
 * The toolkit edge stays there so that this module, like the rest of the
 * engine, carries no AWT.
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
     *
     * [koin] is the launcher's graph. It is held here and by whoever the
     * entrypoint hands it to, and nowhere global. Closing it is the
     * entrypoint's, at process exit.
     */
    data class Result(
        val paths: PlatformPaths,
        val pendingMigration: DataDirMigration.Source?,
        val crashReporter: CrashReporter,
        val koin: KoinApplication,
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
     * The synchronous prologue: log-dir resolution, session tagging, and
     * (for the GUI) the single-instance gate. Everything here is
     * milliseconds-cheap by construction -- the point of the split is
     * that a window can go up immediately after this returns.
     *
     * The log directory is the entry point's to name, before anything it
     * reaches can create a logger (see [LogDirectory]). Named again here
     * for the same paths, which keeps the session id the entry made.
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
        val sessionId = LogDirectory.claim(initialPaths)

        // Beacon: the very first entry in the action ring -- handy when reading a
        // bundle to confirm what process / version / OS produced it.
        ActionRing.record(
            "Launcher started (v${Branding.VERSION}, sessionId=$sessionId, os=${System.getProperty("os.name")})"
        )

        if (singleInstance) {
            // The data dir may not exist yet on a fresh install; the lock file
            // needs its parent. Idempotent, and completeCore re-creates after
            // a potential data-dir move.
            val _ = runCatching { Files.createDirectories(initialPaths.dataDir) }
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
            System.setProperty(LogDirectory.LOGS_DIR_PROPERTY, paths.logsDir.toString())
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
        //
        // Isolated, not startKoin: the global context is reachable from any code in
        // the process, a widget module's jar included, and the graph holds the
        // account store and the session tokens.
        //
        // The eager singletons are made apart from the declaration, so a graph whose start
        // throws half way is closed rather than left with what it already made running.
        val koin = koinApplication(createEagerInstances = false) { modules(launcherModules() + desktopModules() + extraModules) }
        try {
            koin.createEagerInstances()
        } catch (e: Throwable) {
            val _ = runCatching { koin.close() }
            throw e
        }

        return Result(paths, pendingMigration, crashReporter, koin)
    }
}
