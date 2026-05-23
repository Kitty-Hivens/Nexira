package hivens.launcher.bootstrap

import hivens.config.Branding
import hivens.core.diag.ActionRing
import hivens.launcher.CrashReporter
import hivens.launcher.di.appModule
import hivens.launcher.di.networkModule
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
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Everything that has to happen before Compose's `application { ... }`
 * picks up the JVM: log directory resolution so logback.xml's
 * `${nexira.logs.dir}` substitution works on the very first
 * LoggerFactory call, pending data-dir migration, session-id tagging,
 * NetworkState SSL-bypass restore, X11 toolkit override, crash handler,
 * single-instance lock, Koin startup.
 *
 * Result is the small set of values the Compose layer needs to keep
 * threading through: the resolved [PlatformPaths] (for window-side
 * `.show` watcher and data-dir-relative reads) and a possibly non-null
 * [DataDirMigration.Source] that gates whether the first composition
 * shows MigrationScreen or AppRoot.
 *
 * Called from [hivens.ui.Main.main] before `application { ... }`.
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

    /**
     * Run the full pre-Compose pipeline. [extraModules] is appended to
     * the launcher's own Koin modules at [startKoin] time so callers
     * (the ui module) can register their singletons in the same Koin
     * context without LauncherBootstrap having to know about them.
     */
    fun preBoot(extraModules: List<Module> = emptyList()): Result {
        // Resolve logs dir BEFORE any LoggerFactory.getLogger() call so
        // logback.xml (which reads `${nexira.logs.dir}` for its rolling-file
        // appenders) sees the platform-correct path on its very first init.
        // PlatformPaths.system() is pure computation -- no logger init --
        // safe to call before the property is set. DataDirMover and
        // BootstrapConf both have lazy log fields specifically so this
        // ordering works without their applyPending() / read() touching
        // logback first.
        val paths = PlatformPaths.system()
        System.setProperty("nexira.logs.dir", paths.logsDir.toString())

        // NOW safe to apply any pending data-dir move scheduled from the
        // Settings UI. If user clicked "Move data directory" -> picker ->
        // restart, this is where the relocation actually happens. Operation
        // is idempotent; safe to call on every startup. The first log line
        // it produces (only in the actual-move case, no-op otherwise) lands
        // in `paths.logsDir/launcher.log`, not `./logs/launcher.log`.
        //
        // Edge case: if applyPending DOES move the data dir, paths.logsDir
        // points at the old location and logback opens the file there --
        // log entries about the move itself stream to the old path right
        // up until the source dir is deleted. Next startup uses the new
        // path correctly. The user opted into this two-restart flow when
        // they clicked Move, so the one-time misdirect is acceptable.
        DataDirMover.applyPending()

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

        System.setProperty("skiko.fps.limit", "60")

        // X11 WM_CLASS override. See XToolkitOverride doc for the
        // cross-vendor reflection rationale; the JVM must have been
        // launched with `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`.
        XToolkitOverride.applyLinuxAppClassName()

        // One INFO line per launch summarising toolkit + Wayland/X11 env --
        // gives every user-attached launcher.log enough context for triage.
        DisplayDiagnostics.logEnvironment()

        Files.createDirectories(paths.dataDir)

        // Constructed here (pre-Koin) so the uncaught-exception handler below
        // can capture an instance with the resolved PlatformPaths. A parallel
        // Koin singleton wires the same shape for post-Koin consumers; they
        // share no mutable state so the parallel instances are functionally
        // identical.
        val crashReporter = CrashReporter(paths)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val handlerLog = LoggerFactory.getLogger("CrashHandler")
            handlerLog.error("Uncaught exception on thread '${thread.name}'", throwable)
            runCatching {
                val report     = crashReporter.generate(throwable, thread)
                val reportFile = crashReporter.saveToDisk(report)
                SwingUtilities.invokeLater { crashReporter.showCrashDialog(report, reportFile) }
            }
        }

        // Single-instance lock acquired BEFORE migration is consulted. Two
        // launchers started close together would otherwise both render the
        // MigrationScreen and race on file copies. DataDirMigration's
        // emptiness check is taught to ignore .lock / .show / .migrated so
        // its first-run trigger still fires.
        if (!SingleInstance.acquire(paths.dataDir)) exitProcess(0)

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
        startKoin { modules(listOf(networkModule, appModule) + extraModules) }

        return Result(paths, pendingMigration, crashReporter)
    }
}
