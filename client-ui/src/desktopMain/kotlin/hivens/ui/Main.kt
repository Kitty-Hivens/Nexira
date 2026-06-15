package hivens.ui

import androidx.compose.ui.window.application
import hivens.core.api.interfaces.ISettingsService
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.diag.ShellRecovery
import hivens.launcher.diag.UiRecoverySignal
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.identity.SkinManager
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.puppet.PuppetServerLoader
import hivens.config.Storage
import hivens.ui.audio.AudioPlayer
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.utils.GameConsoleService
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory
import hivens.launcher.AutoSyncService
import hivens.launcher.update.UpdateService
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
import hivens.widget.api.flowSource
import hivens.widget.api.suspendCommand
import hivens.widget.generated.GeneratedWidgetRegistry
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.context.stopKoin
import org.koin.dsl.module

// ─── DI ──────────────────────────────────────────────────────────────────────

/**
 * Compose-aware Koin singletons that the ui module owns. Passed into
 * [LauncherBootstrap.preBoot] so they land in the same Koin context as
 * the launcher's own modules without inverting the module dependency
 * direction (client-launcher must not know about client-ui types).
 */
val uiModule = module {
    single { SkinManager(get(), get()) }
    single { GameConsoleService(get()) }

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
    single { SessionRegistry(appScope = get()) }
    single {
        val settingsService: ISettingsService = get()
        LaunchDriver(
            controller      = get(),
            notifications   = get(),
            indications     = get(),
            sessions        = get(),
            gameConsole     = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        )
    }
}

// ─── Entry Point ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalResourceApi::class)
fun main() {
    val boot = LauncherBootstrap.preBoot(listOf(uiModule))

    // Puppet mode: opt-in localhost HTTP control surface for automated
    // UI driving (see hivens.ui.puppet.PuppetServerLifecycle + Loader).
    // Two-layer gating: build-time SPI (RealPuppetServer ships only when
    // -PauraPuppetPort=N is on the Gradle command line) + runtime system
    // property (-Dnexira.puppet.port=N must be set to actually bind).
    // MUST run after Koin (LauncherBootstrap.preBoot) so PuppetRegistry-
    // using Composables can resolve their dependencies, and before
    // `application` so the server is listening when the first Composable
    // registers itself.
    PuppetServerLoader.instance.startIfRequested()

    // Process-lifetime teardown. Puppet + Koin are set up once here, outside
    // the shell restart loop, so they get torn down once at process exit --
    // NOT from a composition DisposableEffect, which also fires when the shell
    // is disposed on a crash and would then stop Koin out from under the
    // recovery restart (the next `application {}` would koinInject() into a
    // dead context). `application(exitProcessOnExit = true)` exits via
    // exitProcess on a normal window close, so this hook still runs then.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { PuppetServerLoader.instance.stop() }
            runCatching { stopKoin() }
        },
    )

    runShellWithRecovery(boot)
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
 * Koin singletons and the data dirs are created in [LauncherBootstrap.preBoot]
 * -- outside this loop -- so a restart keeps the user's data, session and audio
 * playback; only transient composition state (current screen, scroll) is lost.
 */
private fun runShellWithRecovery(boot: LauncherBootstrap.Result) {
    val log = LoggerFactory.getLogger("ShellRecovery")
    while (true) {
        // Safe mode runs a standalone window that does NOT build the shell
        // scaffolding (Koin inject, tray init, theme, widget kernel) -- a crash
        // anywhere in that scaffolding is what latched safe mode, so re-running
        // it would just crash again. Deciding here (not inside AppShell) is what
        // makes the safe surface actually reachable.
        val safe = UiRecoverySignal.safeMode.value
        val outcome = runCatching {
            if (safe) {
                application { SafeModeWindow(onQuit = { exitApplication() }) }
            } else {
                application { AppShell(boot) }
            }
        }
        if (outcome.isSuccess) return

        val crash = outcome.exceptionOrNull() ?: return
        log.error(
            if (safe) "Safe-mode window crashed -- giving up" else "Shell composition crashed -- attempting recovery",
            crash,
        )

        val saved = runCatching {
            val report = boot.crashReporter.generate(crash, Thread.currentThread())
            report to boot.crashReporter.saveToDisk(report)
        }.getOrNull()

        when (UiRecoverySignal.recordShellCrash()) {
            ShellRecovery.RETRY     -> log.warn("Restarting shell with a fresh composition")
            ShellRecovery.SAFE_MODE -> log.warn("Crash loop detected -- falling back to safe mode")
            ShellRecovery.FATAL     -> {
                log.error("Safe mode itself crashed -- giving up on the UI")
                if (saved != null) {
                    runCatching {
                        SwingUtilities.invokeAndWait { boot.crashReporter.showCrashDialog(saved.first, saved.second) }
                    }
                }
                return
            }
        }
    }
}
