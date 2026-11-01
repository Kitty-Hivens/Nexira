package hivens.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import hivens.core.api.interfaces.IPackRepository
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
import hivens.ui.components.LauncherUpdateState
import hivens.ui.platform.DesktopHostActions
import hivens.ui.platform.HostActions
import hivens.core.activity.ActivityRegistry
import hivens.launcher.PackInstallService
import hivens.core.update.PackUpdateStatusHub
import hivens.ui.activity.ActivityCommands
import hivens.ui.activity.ActivityDriver
import hivens.ui.activity.SelectionRegistry
import hivens.ui.screens.library.content.ContentIconStore
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
import hivens.ui.audio.PlaybackRouter
import hivens.ui.audio.WallpaperSession
import hivens.ui.audio.MediaSessionBridge
import hivens.ui.audio.AudioOutput
import hivens.ui.audio.SkinemaEngines
import hivens.ui.audio.decodeArtwork
import hivens.ui.audio.SystemAudioOutput
import hivens.ui.background.BackgroundOptimizer
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.media.VideoCacheService
import hivens.media.YtDlpService
import hivens.tray.LibTrayController
import hivens.tray.TrayController
import hivens.ui.background.BackgroundManager
import hivens.ui.layout.LayoutGraphFlushHook
import hivens.launcher.legacy.RetiredDataSweeper
import hivens.ui.legacy.RetiredClientsGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogRetention
import hivens.ui.utils.PreferenceWriter
import hivens.widget.model.DefaultLayout
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory
import hivens.launcher.instance.InstanceContentUpdater
import hivens.update.UpdateService
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.ui.widgets.state.WidgetStateFlushHook
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.ui.widgets.state.WidgetStateGc
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.widget.api.SurfaceFamilies
import hivens.ui.widgets.modules.DesktopWidgetModuleHost
import hivens.ui.widgets.modules.WidgetModules
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.command
import hivens.ui.debug.DebugOverlayState
import hivens.ui.feature.catalogue.browse.BrowseController
import hivens.ui.feature.catalogue.browse.BrowseSession
import hivens.ui.feature.catalogue.browse.ProjectBrowseSession
import hivens.ui.feature.catalogue.browse.BrowseTags
import hivens.widget.api.flowSource
import hivens.widget.api.suspendCommand
import hivens.widget.generated.GeneratedWidgetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
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
    includes(
        uiCoreModule(
            builtIn = GeneratedWidgetRegistry,
            moduleHost = { directory, dataDir ->
                DesktopWidgetModuleHost(directory, shadowDir = dataDir.resolve("cache").resolve("widget-modules"))
            },
            platformCommands = { registry ->
                val updates: UpdateService = get()
                val scope: CoroutineScope = get()
                // Exercises the suspend adapter in the real graph (scope captured at
                // registration) and stays a seam until a dispatcher uses it.
                registry.register(Commands.CheckUpdate, suspendCommand(scope) { updates.checkForUpdate() })
            },
        ),
    )

    // AWT-backed icon downscaler for the content scanner (the engine module
    // stays free of java.desktop; the seam interface lives in core).
    single<IconProcessor> { ImageIoIconProcessor() }
    // System tray (client-tray seam): one libtray-backed impl. A plain single, so
    // client-cli -- which never injects it -- never loads libtray's natives.
    single<TrayController> { LibTrayController() }
    // The leftover-clients chore. The gate is what the reminder's action opens;
    // the sweeper is registered here rather than beside the scanner because its
    // one hook belongs to a client-ui type: the default skins are read out of a
    // client jar, and on an upgraded install the retired tree is the only place
    // one lives until a pack is installed. Listing them extracts and caches the
    // PNGs for good -- nine off a modern client, the Steve and Alex pair off a
    // 1.12.2 one -- so the sweep does it once before the first deletion.
    // Otherwise somebody who cleared the folders first would find the wardrobe's
    // defaults gone for a reason they could not act on.
    single { RetiredClientsGate() }
    single {
        val skins: DefaultSkinProvider = get()
        RetiredDataSweeper(
            dataDir = get<Path>(),
            beforeFirstDelete = { withContext(Dispatchers.IO) { skins.list() } },
        )
    }
    // Audio engine for the player widgets. Survives recomposition; playback state
    // lives in the singleton so swapping the widget out of the layout does not stop
    // playback. Loudness is seeded from and written back to the settings file, the
    // same way the notification mute is: it is a property of the listener, and it
    // used to be reset to full on every launch.
    single {
        val settings: ISettingsService = get()
        val saved = settings.getSettings()
        AudioPlayer(
            scope         = get(),
            initialVolume = saved.audioVolume,
            persistVolume = { level ->
                settings.updateSettings { it.copy(audioVolume = level) }
            },
            initialQueue  = saved.audioQueue.mapNotNull { runCatching { Path.of(it) }.getOrNull() },
            initialIndex  = saved.audioQueueIndex,
            // Optional, and read as optional. The doc on this parameter says losing
            // the named output must never cost the sound itself, and a get() that
            // throws is exactly that cost, paid by everything else in the process
            // as well.
            engines       = SkinemaEngines(getOrNull()),
            artworkDecoder = { decodeArtwork(it) },
            persistQueue  = { files, index ->
                settings.updateSettings { it.copy(
                        audioQueue      = files.map { it.toString() },
                        audioQueueIndex = index,
                    ) }
            },
        )
    }

    // What a widget asks about playback, and the one place the answer comes from.
    //
    // It used to be provided by every player widget into the widget service
    // registry and read by one consumer, which had a fallback to this same engine
    // for when none was mounted. Every path already ended at one object, so the
    // round trip decided which wrapper was asked and nothing else: ten
    // registrations, one reader, and a churn the drag ghost could steal from,
    // since a ghost and its source share an instance id and the ghost's dispose
    // unregistered what the source had provided.
    //
    // App-static instead, like the data sources and the commands beside it. The
    // widgets read the contract rather than the engine, which is what lets the
    // thing behind it change without any of them knowing.
    single { WallpaperSession(get()) }
    single { PlaybackRouter(get(), MusicPlayerServiceImpl(get()), get<WallpaperSession>()) }
    // Bound to the same instance rather than built twice: the background asks for
    // the router by its own type to say who owns the session, and everything else
    // asks for the contract. Two instances would mean the switch and the readers
    // disagreeing about who is playing.
    single<MusicPlayerService> { get<PlaybackRouter>() }

    // Where the sound leaves. One connection to the sound server for the process,
    // opened on the first track rather than at startup, and a stream per track on
    // top of it. A machine it cannot reach falls back to the line skinema opens
    // for itself, so this is an improvement to how the sound is labelled and never
    // a condition on it playing.
    // Bound by the interface the player asks for, not by the class that implements
    // it. Registered under the concrete type, the lookup for AudioOutput found
    // nothing and took the whole launcher down before its first frame.
    single<AudioOutput>(createdAtStart = false) { SystemAudioOutput() }

    // What the desktop sees of the player: MPRIS on Linux, the platform's own
    // elsewhere. createdAtStart because nothing composes it -- a media session is
    // not a widget, and a launcher whose keys only work once somebody has opened
    // the right screen is a launcher whose keys do not work.
    //
    // It claims no device, so it neither competes with the engine nor depends on
    // one being open, and a desktop with no session bus simply gets nothing.
    single(createdAtStart = true) {
        MediaSessionBridge(
            player = get(),
            scope  = get(),
            artDir = get<Path>().resolve("media-session"),
        ).also { it.start() }
    }

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
    single<HostActions> { DesktopHostActions(get(), get()) }
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
            // The pack registry opens its database on first use, so it is used here,
            // on this thread: the first screen to read it would otherwise open it on
            // the UI thread. Whatever goes wrong is the registry's to report, and it
            // is no reason to stop the boot.
            runCatching { GlobalContext.get().get<IPackRepository>().observe() }
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
                            locale = pre.peek.locale,
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

        // A crash that ran through a widget module's code is the module's. Switching
        // it off before the restart is what makes the restart work, where retrying
        // the same crash would only count down to safe mode. The crash still counts:
        // if the module was not the whole story, safe mode is still the floor.
        if (!safe) {
            runCatching {
                val modules = GlobalContext.getOrNull()?.getOrNull<WidgetModules>()
                modules?.culpritOf(crash)?.let { modules.switchOffAfterCrash(it, crash) }
            }.onFailure { log.warn("Could not trace the crash to a widget module", it) }
        }

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
