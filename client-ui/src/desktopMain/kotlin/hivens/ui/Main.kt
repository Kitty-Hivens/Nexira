package hivens.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import hivens.core.api.interfaces.ISettingsService
import hivens.core.io.IconProcessor
import hivens.ui.bootstrap.GuiBootstrap
import hivens.ui.bootstrap.RecoveryEntry
import hivens.ui.threshold.BootOutcome
import hivens.ui.threshold.BootStage
import hivens.ui.threshold.toStage
import hivens.ui.diag.CrashDialog
import hivens.ui.diag.ShellRecovery
import hivens.ui.diag.UiRecoverySignal
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.launcher.platform.PlatformPaths
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.ClanRoleProvider
import hivens.ui.identity.SkinManager
import hivens.ui.navigation.NavRequests
import hivens.core.activity.ActivityRegistry
import hivens.launcher.PackInstallService
import hivens.core.update.PackUpdateStatusHub
import hivens.ui.activity.ActivityCommands
import hivens.ui.activity.ActivityDriver
import hivens.ui.activity.SelectionRegistry
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.InstallDriver
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.notifications.drivers.PackUpdateDriver
import hivens.ui.platform.ImageIoIconProcessor
import hivens.ui.puppet.PuppetServerLoader
import hivens.config.Storage
import hivens.ui.audio.AudioPlayer
import hivens.ui.background.BackgroundOptimizer
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.media.VideoCacheService
import hivens.media.YtDlpService
import hivens.tray.LibTrayController
import hivens.tray.TrayController
import hivens.ui.layout.LayoutGraphFlushHook
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.widget.model.DefaultLayout
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory
import hivens.launcher.AutoSyncService
import hivens.update.UpdateService
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.ui.widgets.state.WidgetStateFlushHook
import hivens.ui.widgets.state.WidgetStateGc
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.command
import hivens.ui.debug.DebugOverlayState
import hivens.widget.api.flowSource
import hivens.widget.api.suspendCommand
import hivens.widget.generated.GeneratedWidgetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.context.stopKoin
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import org.koin.core.qualifier.named
import org.koin.dsl.module

// ─── DI ──────────────────────────────────────────────────────────────────────

/**
 * Compose-aware Koin singletons that the ui module owns. Passed into
 * [GuiBootstrap.preBoot] so they land in the same Koin context as
 * the launcher's own modules without inverting the module dependency
 * direction (client-launcher must not know about client-ui types).
 */
val uiModule = module {
    single { SkinManager(get(), get()) }
    single { ClanRoleProvider(get()) }
    single { SkinLibrary(get<Path>().resolve("skins"), get()) }
    single { DefaultSkinProvider(get<PlatformPaths>().clientsDir, get<PlatformPaths>().skinCacheDir.resolve("defaults")) }
    single { GameConsoleService(get()) }
    // One owner of console.json for the three surfaces that read it: the shell's
    // window, Settings > Console and the pack's Logs tab.
    single { ConsoleSettingsStore(get<Path>(), get(), get()) }
    // AWT-backed icon downscaler for the content scanner (the engine module
    // stays free of java.desktop; the seam interface lives in core).
    single<IconProcessor> { ImageIoIconProcessor() }
    // Dev UI-debug overlay switchboard. Process-lifetime so the toggle survives
    // shell recomposition + the crash-restart loop; inert on release builds.
    single { DebugOverlayState() }

    // System tray (client-tray seam): one libtray-backed impl. A plain single, so
    // client-cli -- which never injects it -- never loads libtray's natives.
    single<TrayController> { LibTrayController() }

    // Widget kernel registry. KSP-generated; entries land as @Widget
    // composables are added across the codebase. Kernel-1 starts the
    // registry empty -- surface refactors arrive in kernel-3.
    single<WidgetRegistry> { GeneratedWidgetRegistry }

    // Cross-widget service registry (Phase D). One global instance per
    // launcher process. Provider widgets register via provideService
    // from inside their composable body (DisposableEffect-driven
    // lifecycle); consumer widgets read via useService<T>() / its
    // siblings. Wired into the composition root from AppShell so
    // LocalWidgetServiceRegistry resolves before any @Widget renders.
    single { WidgetServiceRegistry() }

    // Reactive data sources widgets bind to via rememberSource -- decouples a
    // widget from the concrete service backing its data. Populated eagerly so
    // the registry is complete before the first widget composes.
    single {
        WidgetDataRegistry().apply {
            register(Sources.AutoSync, flowSource(get<AutoSyncService>().snapshot))
            register(Sources.Notifications, flowSource(get<NotificationArchiveStore>().log))
            register(Sources.DoNotDisturb, flowSource(get<NotificationCenter>().doNotDisturb))
        }
    }

    // Reactive write side: commands widgets fire via rememberCommand /
    // rememberAction without injecting the backing service. Services resolved
    // eagerly, like the data registry above. ClearNotifications is consumed by the
    // notification-history widget; CheckUpdate exercises the suspend adapter in the
    // real graph (scope captured at registration) and stays a seam until a
    // dispatcher uses it.
    single {
        val archive: NotificationArchiveStore = get()
        val updates: UpdateService = get()
        val scope: CoroutineScope = get()
        val center: NotificationCenter = get()
        WidgetCommandRegistry().apply {
            register(Commands.ClearNotifications, command { archive.clear() })
            register(Commands.CheckUpdate, suspendCommand(scope) { updates.checkForUpdate() })
            register(Commands.SetDoNotDisturb, command { center.setDoNotDisturb(it) })
        }
    }

    // Widget layout graph persistence. Default graph lives at
    // /widget/default-layout.json inside :widget-model; first run seeds the
    // file, thereafter the on-disk copy is the source of truth. Reactive via
    // StateFlow so the editor mutates the graph live. Lives in the ui module:
    // the layout graph is UI-shell state, the engine has no reader.
    single {
        val dataDir: Path = get()
        LayoutGraphRepository(
            file         = dataDir.resolve(Storage.LAYOUT_GRAPH_FILE),
            json         = get(),
            scope        = get(),
            defaultGraph = { DefaultLayout.load(get()) },
        )
    }

    // Flush pending debounced layout writes on JVM shutdown. createdAtStart so
    // the hook registers during startKoin{}. Runs in parallel with the scope
    // cancellation hook (JVM shutdown hooks run concurrently); flush() is
    // mutex-locked and cancellation-safe, so the race is fine.
    single(createdAtStart = true) { LayoutGraphFlushHook(get()) }

    // Per-instance widget runtime state (rememberWidgetState): the store backs the
    // WidgetStateHost local; the GC collector prunes orphans off the live layout
    // graph; the flush hook lands the debounced write on quit. GC + flush hook are
    // createdAtStart so they wire up before any stateful widget composes / before exit.
    single { WidgetStateStore(get<Path>().resolve("widget-state.json"), get(), get()) }
    single(createdAtStart = true) { WidgetStateGc(repo = get(), store = get(), scope = get()) }
    single(createdAtStart = true) { WidgetStateFlushHook(get()) }

    // Editor mutation facade. Holds no state itself; fires LayoutGraph
    // updates into the shared CoroutineScope so callers stay
    // fire-and-forget.
    single { EditModeController(repo = get(), scope = get()) }

    // Audio engine for MusicPlayerWidget. Survives recomposition;
    // playback state lives in the singleton so swapping the widget
    // out of the layout does not stop playback.
    single { AudioPlayer(scope = get()) }

    // Media resolvers feeding the local-only Skinema player (client-media).
    // Wired here: the UI is their only consumer, the launch engine does not
    // know the module. "direct" channel: public CDNs / GitHub, not SC-proxied.
    single { VideoCacheService(dir = get<Path>().resolve("video-cache"), transfers = get(), scope = get()) }
    single {
        YtDlpService(
            toolsDir      = get<Path>().resolve("tools"),
            videoCacheDir = get<Path>().resolve("video-cache"),
            transfers     = get(),
            scope         = get(),
        )
    }

    // Wallpaper transcode. A singleton because the work runs in the app scope and
    // outlives the settings screen: the picker has to find the transcode it started
    // still there (and still cancellable) after a trip through another screen.
    single {
        BackgroundOptimizer(
            cacheDir = get<Path>().resolve("background-cache"),
            scope    = get(),
        )
    }

    // Preset storage. One file per preset under <dataDir>/presets/.
    // Atomic write + share-by-file design lets users export to
    // anywhere.
    single {
        val dataDir: Path = get()
        PresetRepository(
            presetsDir = dataDir.resolve(Storage.PRESETS_DIR),
            json       = get(),
        )
    }

    // Notification subsystem: data-only state holders (Center +
    // Registry) are pure singletons; LaunchDriver bridges them to
    // LauncherController for both pack and SC-server flows (the
    // observer is keyed by LaunchTarget so the same singleton handles
    // both kinds of launches without aliasing notifications).
    // Durable message log feeding the notification-history widget. Disk-backed
    // (<dataDir>/notifications.json), survives auto-dismiss + restart.
    single {
        val dataDir: Path = get()
        NotificationArchiveStore(
            file  = dataDir.resolve("notifications.json"),
            json  = get(),
            scope = get(),
        )
    }
    single {
        val settings: ISettingsService = get()
        NotificationCenter(
            archive             = get<NotificationArchiveStore>()::record,
            // Seed the popup-mute from the persisted preference and write the
            // flip back, so "do not disturb" survives a restart.
            initialDoNotDisturb = settings.getSettings().doNotDisturb,
            persistDoNotDisturb = { value ->
                settings.saveSettings(settings.getSettings().copy(doNotDisturb = value))
            },
        )
    }
    single { IndicationCenter() }
    // One account of what the launcher is doing, read by the activity surface.
    // Redaction, rate limiting and the caps are the registry's own contract --
    // see its KDoc for why each is load-bearing on permanent chrome.
    single { ActivityRegistry(scope = get()) }
    // Turns the capabilities the registry advertises back into calls. Kept
    // out of the model on purpose: a lambda field would break Activity's
    // equality and the registry's throttle depends on it.
    single { ActivityCommands(installs = get(), controller = get(), registry = get()) }
    // What the current view has selected. App-scoped so the surface can read it
    // without knowing which screen published it; the view clears it on the way out.
    single { SelectionRegistry() }
    single { SessionRegistry(appScope = get()) }
    // One-slot handoff: the launch driver parks "needs a code", the shell answers.
    single { hivens.ui.notifications.TwoFactorLaunchGate() }
    single {
        val settingsService: ISettingsService = get()
        LaunchDriver(
            controller      = get(),
            notifications   = get(),
            indications     = get(),
            activities      = get(),
            sessions        = get(),
            gameConsole     = get(),
            appScope        = get(),
            offlineProvider = get(),
            settingsService = settingsService,
            credentialStore = get(),
            twoFactorGate   = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        )
    }
    // Surfaces app-scoped installs (PackInstallService) into the notification
    // center. createdAtStart so its collector is live before the first install
    // can fire; start() launches on the shared app scope.
    single(createdAtStart = true) {
        val settingsService: ISettingsService = get()
        InstallDriver(
            service         = get(),
            notifications   = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        ).also { it.start() }
    }
    // Feeds the self-identifying services (installs, updates, sync) into the
    // activity registry. Launch and game entries come from LaunchDriver, which
    // is the only place that knows which pack a LaunchState belongs to.
    // createdAtStart for the same reason as InstallDriver: the collector must
    // be live before the first report can fire.
    single(createdAtStart = true) {
        ActivityDriver(
            registry   = get(),
            installs   = get<PackInstallService>().installs,
            updates    = get<PackUpdateStatusHub>().statuses,
            sync       = get<AutoSyncService>().snapshot,
            repository = get(),
            appScope   = get(),
        ).also { it.start() }
    }
    // Navigation requests from outside the composition (notification actions,
    // drivers). AppRoot collects and feeds them into the back stack.
    single { NavRequests() }
    // Surfaces pack-update outcomes (background auto-updater + manual flows via
    // the status hub) into the notification center. Same lifecycle rationale as
    // InstallDriver.
    single(createdAtStart = true) {
        val settingsService: ISettingsService = get()
        PackUpdateDriver(
            hub             = get(),
            repository      = get(),
            notifications   = get(),
            nav             = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        ).also { it.start() }
    }
}

// ─── Entry Point ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalResourceApi::class)
fun main(args: Array<String>) {
    val pre = GuiBootstrap.preWindow()

    // Boot straight into recovery when asked (env NEXIRA_RECOVERY / --recovery /
    // a one-shot marker), for a launcher that starts wrong but does not crash.
    // Recovery needs only the data dir, so we skip the whole boot -- Koin, vault,
    // network -- and a broken module cannot take the recovery surface down with it.
    val recovery = RecoveryEntry.resolve(pre.core.initialPaths.dataDir, args)
    if (recovery) UiRecoverySignal.requestRecovery()

    // Process-lifetime teardown. Puppet + Koin come up on the boot thread but
    // are torn down once here at process exit -- NOT from a composition
    // DisposableEffect, which also fires when the shell is disposed on a crash
    // and would then stop Koin out from under the recovery restart (the next
    // `application {}` would koinInject() into a dead context). The explicit
    // exitProcess below routes every exit through this hook.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { PuppetServerLoader.instance.stop() }
            runCatching { stopKoin() }
        },
    )

    // Boot inversion: the window goes up FIRST (ShellHost renders the
    // threshold), and everything slow -- pending data-dir move, migration
    // detect, Koin -- runs here behind the live boot
    // screen. Daemon thread so a user closing the window mid-boot is not
    // held hostage by a stuck phase.
    val bootOutcome = MutableStateFlow<BootOutcome?>(null)
    val bootStage   = MutableStateFlow(BootStage.Files)
    // Dev knob: -Dnexira.boot.slowMs=800 stretches each phase so the
    // threshold can actually be looked at on a warm machine.
    val slowMs = System.getProperty("nexira.boot.slowMs")?.toLongOrNull() ?: 0L
    if (!recovery) thread(name = "nexira-boot", isDaemon = true) {
        runCatching {
            GuiBootstrap.completeBoot(pre, listOf(uiModule)) { phase ->
                bootStage.value = phase.toStage()
                if (slowMs > 0) Thread.sleep(slowMs)
            }
        }.mapCatching { result ->
            // Puppet mode: opt-in localhost HTTP control surface for automated
            // UI driving (see hivens.ui.puppet.PuppetServerLifecycle + Loader).
            // Two-layer gating: build-time SPI (RealPuppetServer ships only when
            // -PauraPuppetPort=N is on the Gradle command line) + runtime system
            // property (-Dnexira.puppet.port=N must be set to actually bind).
            // MUST run after Koin so PuppetRegistry-using Composables can resolve
            // their dependencies, and before Ready is published so the server is
            // already listening when the first shell Composable registers itself
            // (the threshold overlay registers nothing).
            PuppetServerLoader.instance.startIfRequested()
            result
        }.onSuccess { result ->
            bootStage.value   = BootStage.Done
            bootOutcome.value = BootOutcome.Ready(result)
            // Training run for the class archive: -Dnexira.trainAndExit=<ms> boots
            // to a rendered shell, waits for the first frames so the UI path's
            // classes are actually loaded, then exits CLEANLY. The clean exit is
            // the point -- CDS writes the archive at VM exit, so a killed process
            // leaves nothing behind. Opt-in only; a normal run never sets it.
            System.getProperty("nexira.trainAndExit")?.let { raw ->
                val settleMs = (raw.toLongOrNull() ?: 3_000L).coerceIn(0L, 60_000L)
                thread(name = "nexira-train-exit", isDaemon = true) {
                    Thread.sleep(settleMs)
                    LoggerFactory.getLogger("Main")
                        .info("trainAndExit: boot complete, exiting to flush the class archive")
                    exitProcess(0)
                }
            }
        }.onFailure { e ->
            LoggerFactory.getLogger("Main").error("Boot failed before the shell could start", e)
            runCatching {
                val reporter = pre.crashReporter.get()
                reporter.saveToDisk(reporter.generate(e, Thread.currentThread()))
            }
            bootOutcome.value = BootOutcome.Failed(e)
        }
    }

    runShellWithRecovery(pre, bootOutcome, bootStage)

    // The recovery loop runs `application(exitProcessOnExit = false)`, so a
    // normal quit RETURNS here instead of the framework killing the JVM.
    // Exit explicitly: lingering non-daemon threads (tray, HTTP pools, AWT)
    // must not keep a quit launcher alive as a zombie process.
    exitProcess(0)
}

/**
 * Run the Compose shell inside a restart loop -- the UI self-healing core.
 * `application {}` blocks until every window closes; if the shell composition
 * throws instead, the exception unwinds it here and we re-enter with a fresh
 * composition ("reload the shell"). [UiRecoverySignal] bounds the restarts: a
 * crash loop latches safe mode (a quit-only surface that skips the widget
 * kernel), and a crash while already in safe mode falls back to the terminal
 * Swing crash dialog.
 *
 * Koin singletons and the data dirs are created in [GuiBootstrap.preBoot]
 * -- outside this loop -- so a restart keeps the user's data, session and audio
 * playback; only transient composition state (current screen, scroll) is lost.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun runShellWithRecovery(
    pre: GuiBootstrap.PreBoot,
    bootOutcome: MutableStateFlow<BootOutcome?>,
    bootStage: MutableStateFlow<BootStage>,
) {
    val log = LoggerFactory.getLogger("ShellRecovery")
    var restarts = 0
    while (true) {
        val isRestart = restarts > 0
        restarts++
        // Safe mode runs a standalone window that does NOT build the shell
        // scaffolding (Koin inject, tray init, theme, widget kernel) -- a crash
        // anywhere in that scaffolding is what latched safe mode, so re-running
        // it would just crash again. Deciding here (not inside the shell) is what
        // makes the safe surface actually reachable.
        val safe = UiRecoverySignal.safeMode.value
        val reason = UiRecoverySignal.recoveryReason.value
        val outcome = runCatching {
            // exitProcessOnExit = false is what makes this loop REAL: the
            // default true had `application` call exitProcess(0) the moment
            // exitApplication() ran, killing the JVM before the crash-consume /
            // report / retry code below could execute -- a render-thread crash
            // stashed by the handler died silently with only shutdown-hook
            // lines in the log. With false, `application` returns and the
            // recovery below actually runs; main() owns the final exitProcess.
            application(exitProcessOnExit = false) {
                // Convert a render/recompose crash into a clean restart. The
                // default WindowExceptionHandler rethrows onto the AWT event
                // thread, which logs it and keeps the now-dead window alive --
                // `application {}` then never returns and this loop never fires
                // (the frozen-window failure). Ours stashes the crash and exits,
                // so the loop below recovers it exactly like a thrown one. Wraps
                // safe mode too: a crash there is stashed identically, and
                // recordShellCrash() already returns FATAL once safe mode is
                // latched, so we reach the terminal dialog instead of hanging.
                val handler = remember {
                    WindowExceptionHandlerFactory {
                        WindowExceptionHandler { crash ->
                            UiRecoverySignal.recordPendingCrash(crash)
                            exitApplication()
                        }
                    }
                }
                CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides handler) {
                    if (reason != UiRecoverySignal.RecoveryReason.None) {
                        // Crash-loop or a user request both route to the recovery
                        // surface; it needs only the data dir, so it renders even
                        // when boot itself is broken (or skipped).
                        RecoveryWindow(
                            dataDir = pre.core.initialPaths.dataDir,
                            reason = reason,
                            onExit = { exitApplication() },
                        )
                    } else {
                        // The restart flag lets ShellHost skip the threshold only
                        // on a genuine recovery re-entry with boot done -- a first
                        // boot that finished before the window composed still
                        // plays it.
                        ShellHost(pre, bootOutcome, bootStage, isRestart = isRestart)
                    }
                }
            }
        }

        // A shell crash surfaces two ways: it unwinds `application {}` (a crash in
        // initial/main-thread composition), or the render path swallowed it on the
        // AWT thread and the handler stashed it above. Consume unconditionally so a
        // stashed crash never leaks into the next iteration.
        val pending = UiRecoverySignal.consumePendingCrash()
        val crash = outcome.exceptionOrNull() ?: pending ?: return
        log.error(
            if (safe) "Safe-mode window crashed -- giving up" else "Shell composition crashed -- attempting recovery",
            crash,
        )

        val saved = runCatching {
            val reporter = pre.crashReporter.get()
            val report = reporter.generate(crash, Thread.currentThread())
            report to reporter.saveToDisk(report)
        }.getOrNull()

        when (UiRecoverySignal.recordShellCrash(crash = crash)) {
            ShellRecovery.RETRY     -> {
                log.warn("Restarting shell with a fresh composition")
                UiRecoverySignal.markRecovered()
            }
            ShellRecovery.SAFE_MODE -> log.warn("Crash loop detected -- falling back to safe mode")
            ShellRecovery.FATAL     -> {
                log.error("Safe mode itself crashed -- giving up on the UI")
                if (saved != null) {
                    runCatching {
                        SwingUtilities.invokeAndWait { CrashDialog.show(saved.first, saved.second) }
                    }
                }
                return
            }
        }
    }
}
