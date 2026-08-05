package hivens.ui.bootstrap

import hivens.launcher.CrashReporter
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.ui.diag.CrashDialog
import org.koin.core.module.Module
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * The GUI's pre-Compose pipeline, composed from [LauncherBootstrap]'s
 * AWT-free pieces plus the toolkit-touching steps that must not enter the
 * headless CLI's reachable graph: the Skiko cap, the X11 WM_CLASS override,
 * the display diagnostics line, and a crash handler that surfaces the Swing
 * dialog.
 *
 * Split to put a window up before the heavy boot work runs:
 *
 *   - [preWindow] is synchronous and cheap: everything that MUST precede
 *     AWT/toolkit init (WM_CLASS reflection, skiko cap, single-instance
 *     gate, log dir) plus a direct [SettingsPeek] of settings.json for the
 *     window-creation-time values (undecorated chrome, locale) that
 *     normally live behind Koin.
 *   - [completeBoot] is the slow remainder (data move, NetworkState, Koin),
 *     run by hivens.ui.Main on a background thread behind the live
 *     boot-threshold window.
 */
object GuiBootstrap {

    /**
     * Values the window host needs before Koin exists. [crashReporter] is a
     * mutable ref: it starts wired to the initial (pre-move) paths so a
     * crash during boot still produces a report, and [completeBoot] swaps in
     * the post-move instance.
     */
    class PreBoot(
        val core: LauncherBootstrap.PreWindow,
        val peek: SettingsPeek,
        val crashReporter: AtomicReference<CrashReporter>,
    )

    /**
     * Run the synchronous pre-window pipeline. Cheap by construction; a
     * window may be created immediately after this returns.
     */
    fun preWindow(): PreBoot {
        val core = LauncherBootstrap.preWindow(singleInstance = true)

        // Present on the display's vsync. This is Skiko's default; stated
        // explicitly so the pacing intent is visible and survives a default
        // change. (The `skiko.fps.limit` property set here before was a no-op
        // -- not a property Skiko reads -- so nothing was ever capped to 60.)
        System.setProperty("skiko.vsync.enabled", "true")

        // X11 WM_CLASS override. See XToolkitOverride doc for the
        // cross-vendor reflection rationale; the JVM must have been
        // launched with `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`.
        // Must run before the AWT toolkit initializes -- i.e. before any
        // window -- which is why it lives here and not in the boot thread.
        XToolkitOverride.applyLinuxAppClassName()

        // One INFO line per launch summarising toolkit + Wayland/X11 env --
        // gives every user-attached launcher.log enough context for triage.
        DisplayDiagnostics.logEnvironment()

        // Pre-move reporter: good enough for a crash during boot. Swapped
        // for the post-move instance by completeBoot.
        val reporter = AtomicReference(CrashReporter(core.initialPaths))
        installGuiCrashHandler(reporter)

        // A launcher that has never run has no settings file, and the two things
        // the very first window shows -- its language and whether it is dark --
        // would otherwise come from field defaults that know nothing about this
        // machine. Seeded before the peek so the boot threshold itself opens in
        // the user's language, not just the shell behind it.
        FirstRunDefaults.seed(core.initialPaths.dataDir)

        val peek = SettingsPeek.read(core.initialPaths.dataDir)

        return PreBoot(core, peek, reporter)
    }

    /**
     * Run the slow boot remainder. Called from the boot thread in
     * hivens.ui.Main; [onPhase] feeds the threshold screen's progress bar.
     */
    fun completeBoot(
        pre: PreBoot,
        extraModules: List<Module> = emptyList(),
        onPhase: (LauncherBootstrap.Phase) -> Unit = {},
    ): LauncherBootstrap.Result {
        val core = LauncherBootstrap.completeCore(pre.core, relockOnMove = true, onPhase = onPhase)
        pre.crashReporter.set(core.crashReporter)
        return LauncherBootstrap.finishBoot(core, extraModules, onPhase)
    }

    /**
     * GUI crash handler: persists the report AND surfaces the Swing dialog.
     * This is the single edge that would otherwise drag AWT/Swing into the
     * headless engine module. Reads the reporter through the ref so a crash
     * after a data-dir move reports against the live paths.
     */
    private fun installGuiCrashHandler(crashReporter: AtomicReference<CrashReporter>) {
        // Touched while the process is healthy. The dialog is loaded lazily
        // otherwise, and the one moment it is wanted is the one where loading a
        // class may no longer work -- a replaced image, a rebuild over a live
        // process. The log this was found in shows exactly that: the crash was
        // reported to disk and then the dialog meant to surface it failed too.
        runCatching { CrashDialog::class.java.name }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val handlerLog = LoggerFactory.getLogger("CrashHandler")
            handlerLog.error("Uncaught exception on thread '${thread.name}'", throwable)
            // This handler is for crashes OFF the composition path (background
            // threads, coroutines). Shell-composition crashes unwind
            // `application {}` and are recovered by the restart loop in
            // hivens.ui.Main instead -- they never reach here.
            runCatching {
                val reporter   = crashReporter.get()
                val report     = reporter.generate(throwable, thread)
                val reportFile = reporter.saveToDisk(report)
                // invokeLater dispatches AFTER this runCatching returns, so the
                // dialog runs unguarded on the EDT. Any throw there -- a missing
                // dialog class in a stale/packaged build, no display, a Swing
                // error -- would land back in THIS handler and loop forever,
                // flooding the log. Guard it so a failed dialog degrades to the
                // saved report plus one log line.
                SwingUtilities.invokeLater {
                    runCatching { CrashDialog.show(report, reportFile) }
                        .onFailure { handlerLog.error("Could not surface the crash dialog; report saved to {}", reportFile, it) }
                }
            }.onFailure { handlerLog.error("Crash reporting itself failed", it) }
        }
    }
}
