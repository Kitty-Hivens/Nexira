package hivens.ui.bootstrap

import hivens.launcher.CrashReporter
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.ui.diag.CrashDialog
import org.koin.core.module.Module
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities

/**
 * The GUI's pre-Compose pipeline, composed from [LauncherBootstrap]'s
 * AWT-free pieces plus the toolkit-touching steps that must not enter the
 * headless CLI's reachable graph: the Skiko cap, the X11 WM_CLASS override,
 * the display diagnostics line, and a crash handler that surfaces the Swing
 * dialog. Called from hivens.ui.Main.main before `application { ... }`.
 */
object GuiBootstrap {

    /**
     * Run the full pre-Compose pipeline for the GUI launcher. [extraModules]
     * is appended to the launcher's own Koin modules at startKoin time so
     * the ui module's singletons land in the same Koin context.
     */
    fun preBoot(extraModules: List<Module> = emptyList()): LauncherBootstrap.Result {
        val core = LauncherBootstrap.prepareCore()

        System.setProperty("skiko.fps.limit", "60")

        // X11 WM_CLASS override. See XToolkitOverride doc for the
        // cross-vendor reflection rationale; the JVM must have been
        // launched with `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`.
        // Runs after prepareCore() set nexira.logs.dir so the diagnostics
        // line below lands in the configured launcher.log.
        XToolkitOverride.applyLinuxAppClassName()

        // One INFO line per launch summarising toolkit + Wayland/X11 env --
        // gives every user-attached launcher.log enough context for triage.
        DisplayDiagnostics.logEnvironment()

        installGuiCrashHandler(core.crashReporter)

        return LauncherBootstrap.finishBoot(core, extraModules, singleInstance = true)
    }

    /**
     * GUI crash handler: persists the report AND surfaces the Swing dialog.
     * This is the single edge that would otherwise drag AWT/Swing into the
     * headless engine module.
     */
    private fun installGuiCrashHandler(crashReporter: CrashReporter) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val handlerLog = LoggerFactory.getLogger("CrashHandler")
            handlerLog.error("Uncaught exception on thread '${thread.name}'", throwable)
            // This handler is for crashes OFF the composition path (background
            // threads, coroutines). Shell-composition crashes unwind
            // `application {}` and are recovered by the restart loop in
            // hivens.ui.Main instead -- they never reach here.
            runCatching {
                val report     = crashReporter.generate(throwable, thread)
                val reportFile = crashReporter.saveToDisk(report)
                SwingUtilities.invokeLater { CrashDialog.show(report, reportFile) }
            }
        }
    }
}
