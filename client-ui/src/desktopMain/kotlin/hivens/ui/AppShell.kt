package hivens.ui

import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import hivens.ui.components.QuitWithGameHost
import hivens.ui.components.QuitGate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import coil3.disk.directory
import hivens.config.Branding
import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyReason
import hivens.core.data.ReadOnlyStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ModuleId
import hivens.core.data.PackAuthRequirement
import hivens.core.launch.LaunchLogEvent
import hivens.core.data.ThemeMode
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.update.ApplyRecovery
import hivens.launcher.update.PackAutoUpdateService
import hivens.core.diag.ActionRing
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.platform.AppRelauncher
import hivens.ui.console.ConsoleCommands
import hivens.ui.debug.DebugOverlay
import hivens.ui.debug.DebugOverlayState
import hivens.ui.debug.IdentitySlotChromeModifier
import hivens.ui.debug.IdentityWidgetDecorator
import hivens.widget.api.LocalSlotChromeModifier
import hivens.widget.api.LocalWidgetDecorator
import hivens.ui.bootstrap.ShellStartup
import hivens.ui.bootstrap.StartupPolicy
import hivens.ui.diag.RenderBackend
import hivens.ui.diag.probe
import hivens.ui.diag.SkinemaGate
import hivens.ui.diag.UiRecoverySignal
import hivens.auth.AccountStore
import hivens.core.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.tray.TrayController
import hivens.tray.TrayStrings
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalWindowHide
import hivens.ui.chrome.ShellChord
import hivens.ui.chrome.resolveShellChord
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowHost
import hivens.ui.chrome.DesktopWindowFrame
import hivens.ui.chrome.LocalWindowFrame
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.LocalCustomChromeAvailable
import hivens.ui.chrome.LocalWindowCapabilities
import hivens.ui.chrome.MIN_SHELL_SIZE
import hivens.ui.chrome.WindowResizeHandles
import hivens.ui.chrome.toCapabilities
import hivens.window.OpeningFrame
import hivens.window.WindowHost
import hivens.window.WindowManagerProbe
import hivens.window.WindowSessionRecorder
import hivens.window.fillUnpainted
import hivens.ui.components.UpdateManager
import hivens.core.io.AtomicFiles
import hivens.ui.customization.CustomizationManager
import hivens.ui.customization.LocalCustomization
import hivens.ui.easter.AprilFools
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.editor.EditModeController
import hivens.ui.editor.WidgetGraphReconciler
import hivens.ui.generated.resources.Res
import hivens.ui.appearance.AppearanceStore
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.screens.MigrationScreen
import hivens.ui.theme.NxTheme
import hivens.ui.text.needsCjkFace
import hivens.ui.theme.nexiraCjkFamily
import hivens.ui.theme.SystemTheme
import hivens.ui.theme.ThemeRevealHost
import hivens.ui.theme.rememberThemeReveal
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.launcher.network.ServerProtocolConfig
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.system.SystemNotifier
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.PreferenceWriter
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.legacy.RetiredClientsGate
import hivens.ui.logic.PostLaunchGate
import hivens.ui.logic.PostLaunchMove
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.LocalWidgetEntrance
import hivens.widget.api.LocalMapControls
import hivens.widget.api.LocalViewportScrollbar
import hivens.ui.widgets.NxMapControls
import hivens.ui.widgets.NxViewportScrollbar
import hivens.ui.widgets.PlayedWidgetEntrance
import hivens.widget.api.WidgetSurfaceRenderer
import androidx.compose.foundation.background
import hivens.ui.host.DesktopWindowBackend
import hivens.ui.host.HostedWindow
import hivens.ui.host.MainWindowNavigation
import hivens.ui.host.WindowCommand
import hivens.ui.host.WindowId
import hivens.ui.host.WindowOwner
import hivens.ui.host.WindowRegistry
import hivens.ui.widgets.WidgetSurface
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import androidx.compose.ui.graphics.toArgb
import hivens.ui.theme.NxColor
import java.awt.Color as AwtColor
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

// Windows Application User Model ID for toast routing (libnotify / SystemNotifier).
// Matches the macOS bundleID for a single cross-platform identity; ignored on
// Linux. Windows shows a toast only for an AUMID an installed shortcut carries:
// the installer's shortcuts carry this one (setup.iss), and the portable build,
// which has no shortcut, gets no toasts while Windows still reports them shown.
private const val NEXIRA_APP_ID = "dev.hivens.nexira"

// ─── App Shell ───────────────────────────────────────────────────────────────

/**
 * Compose-side shell content: tray bootstrap + raise-tick .show watcher +
 * migration / AppRoot branch. Runs INSIDE the window that [ShellHost] owns
 * -- the window is created BEFORE Koin so the boot threshold can render, so
 * the window-level callbacks this used to install directly on Window() are
 * late-bound through [chrome] instead. Receives the pre-Compose
 * [LauncherBootstrap.Result] from the boot thread; everything below this
 * is pure Compose.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun FrameWindowScope.AppShellContent(
    boot: LauncherBootstrap.Result,
    windowState: WindowState,
    visibleState: MutableState<Boolean>,
    chrome: WindowChromeHooks,
    opening: OpeningFrame,
    look: ShellLook,
    exitApp: () -> Unit,
) {
    // Tray teardown is composition-scoped: the tray is re-init'd per
    // composition (see the tray LaunchedEffect below), so disposing it here
    // gives a clean shutdown -> init cycle across a shell restart. Process-
    // lifetime teardown (puppet server, Koin) is deliberately NOT here: it
    // also fires when the composition is disposed on a crash, which would stop
    // Koin out from under the recovery restart loop. It lives in a JVM
    // shutdown hook in Main instead.
    val tray: TrayController = koinInject()

    DisposableEffect(Unit) {
        onDispose {
            tray.shutdown()
            SystemNotifier.shutdown()
        }
    }

    val settingsService: ISettingsService      = koinInject()
    val controller: LauncherController         = koinInject()
    val gameConsole: GameConsoleService        = koinInject()
    val debugOverlay: DebugOverlayState        = koinInject()
    val layoutGraphRepo: LayoutGraphRepository = koinInject()
    val widgetRegistry: WidgetRegistry         = koinInject()
    val widgetModules: WidgetModules           = koinInject()
    val widgetServiceRegistry: WidgetServiceRegistry = koinInject()
    val widgetDataRegistry: WidgetDataRegistry = koinInject()
    val appLevel: WidgetScope = koinInject()
    val widgetCommandRegistry: WidgetCommandRegistry = koinInject()
    val widgetStateStore: WidgetStateStore = koinInject()
    val editModeController: EditModeController  = koinInject()
    val sessions: hivens.ui.notifications.SessionRegistry = koinInject()
    // Shared process-lifetime scope (createdAtStart in appModule; canceled
    // by AppCoroutineScopeHook on JVM shutdown). Same instance backs
    // LauncherController.appScope and any other fire-and-forget work.
    val applicationScope: CoroutineScope        = koinInject()
    val preferences: PreferenceWriter           = koinInject()

    val settings = remember { settingsService.getSettings() }

    // Every way out of the launcher goes through here. With a game running it asks
    // first (see QuitWithGameHost); otherwise it quits as it always has.
    val quitGate = remember { QuitGate() }
    val quit: () -> Unit = {
        val state = controller.state.value
        if (controller.runningPackInstanceId.value != null || state is LaunchState.GameRunning || state is LaunchState.Stopping) {
            quitGate.request()
        } else {
            exitApp()
        }
    }

    // The console's own commands, declared in ConsoleCommands. Registered once;
    // the console service is a process singleton. The UI-debug toggle is among
    // them only on a build that has the overlay (F9 stays the primary way in).
    // The input row appears as soon as anything is registered, so from here the
    // console takes typed commands with no game running.
    LaunchedEffect(Unit) {
        val overlayToggle: (() -> Unit)? = if (debugOverlay.available) debugOverlay::toggle else null
        ConsoleCommands.registerAll(
            console = gameConsole,
            scope = applicationScope,
            debugOverlayToggle = overlayToggle,
            // Busy from the first prepare step, not from the moment a game
            // process exists: the download and the unpack run in this process.
            launcherIsBusy = {
                val state = controller.state.value
                state !is LaunchState.Idle && state !is LaunchState.Error
            },
            // Same self-relaunch the recovery restart uses: spawn the binary again
            // and let this process go. False means there is nothing to spawn (a
            // dev run), and then nothing happens at all.
            restartWorld = { if (AppRelauncher.relaunch()) exitProcess(0) else false },
        )
    }

    // The window's state and the one way to change it: maximised and minimised as
    // the system reports them, what the window manager allows, and the system's own
    // frame where there is one, which moves, resizes and snaps the window itself
    // (see WindowHost). Every piece of the custom frame asks it before it offers
    // itself, and nothing here keeps a view of the window of its own.
    val windowHost = remember {
        WindowHost.attach(
            window       = window,
            handle       = window.windowHandle,
            customFrame  = settings.useCustomChrome && WindowManagerProbe.allowsCustomFrame,
            firstRestore = opening.normal,
        ).also { it.keepMinimumSize(MIN_SHELL_SIZE) }
    }
    DisposableEffect(windowHost) { onDispose { windowHost.close() } }
    val windowRegistry: WindowRegistry = koinInject()
    val windowStatus by windowHost.status.collectAsState()
    val windowActions = windowHost.actions.collectAsState()
    val windowCapabilities = remember(windowActions) { derivedStateOf { windowActions.value.toCapabilities() } }

    // Remembers how the window is left, for the next start to open it that way.
    // Only where the window manager lets the window place itself: elsewhere the
    // next start ignores the record anyway (see openingFrame).
    if (WindowManagerProbe.allowsCustomFrame) {
        DisposableEffect(windowHost) {
            val recorder = WindowSessionRecorder.start(windowHost, boot.paths.dataDir, opening.normal)
            onDispose { recorder.close() }
        }
    }

    // Skinema media (FFmpeg natives) can be disabled by boot recovery on an
    // environment where it fails; latch the process gate before the background
    // or any player composes.
    remember { SkinemaGate.enabled = ModuleId.Skinema.id !in settings.disabledModules }

    // Which backend Skiko settled on. Resolved after the first frame, because
    // the layer picks its API while it initialises, and recorded once: a shell
    // that fell back to software rasterises the whole window on the CPU, which
    // is felt across the machine and is otherwise indistinguishable in a report
    // from a launcher that is simply busy.
    LaunchedEffect(window) {
        withFrameNanos { }
        LoggerFactory.getLogger("RenderBackend").info("UI render backend: {}", RenderBackend.probe(window))
    }

    // Window starts visible. Tray is the dock-style fallback for
    // close-while-game-running, not a launcher hide-by-default
    // mode -- a start-in-tray toggle was tried and dropped; it
    // confused users (launcher invisible after first run) without
    // a clear use case. The state itself lives in ShellHost (it is a
    // Window() parameter there); the delegate keeps every reader/writer.
    var isWindowVisible by visibleState

    // Bringing the window back is two writes, not one: setting visible=true
    // alone leaves a window that was taskbar-minimized before it was hidden
    // minimized, so the tray click that asked for it reads as doing nothing.
    val revealWindow: () -> Unit = {
        windowHost.unminimize()
        isWindowVisible = true
    }

    // How the launcher looks and speaks lives in the appearance store, which every
    // composition reads through its sources. The shell draws with the same values
    // and feeds the store what only a host can see: whether the system's scheme can
    // be read, what it says, and how bright the wallpaper is. Made here, during the
    // first composition, so a theme file from a newer build is on record before the
    // read-only notice below looks.
    val appearance: AppearanceStore = koinInject()
    val isDarkTheme by appearance.dark.collectAsState()
    val themeMode by appearance.mode.collectAsState()
    val currentLocale by appearance.locale.collectAsState()
    // Whether the OS scheme is readable at all (no portal backend on Linux -> no).
    // Probed once; drives the System chip's enabled state in the theme island.
    LaunchedEffect(Unit) {
        appearance.setSystemSchemeAvailable(withContext(Dispatchers.IO) { SystemTheme.probe() } != null)
    }
    // System mode: follow the OS scheme while the mode is active -- the cold flow
    // (portal signal on Linux, polling fallback) runs only while collected, so the
    // other modes cost nothing.
    LaunchedEffect(themeMode) {
        if (themeMode != ThemeMode.System) return@LaunchedEffect
        SystemTheme.observe().collect { dark -> appearance.systemSchemeChanged(dark) }
    }

    // The chaos engine is a plain singleton rather than a composable, so it is
    // told what the interface looks like rather than reading it. Both values were
    // mirrored from the style axis; there is one form now, so they are constants
    // and this is the seam where a second one would reach the engine again.
    LaunchedEffect(Unit) {
        AprilFools.styleAnimationMultiplier = 1f
        AprilFools.useFlatSurface           = false
    }

    // One-time registry-aware reconcile of the loaded layout graph. The
    // launcher seeds missing bundled-default surfaces/slots but has no
    // WidgetRegistry, so descriptor-declared container child slots are seeded
    // here -- otherwise a container persisted before child-slot seeding (or one
    // whose descriptor gained a slot) silently refuses nested drops. Idempotent:
    // a healthy graph reconciles to itself and writes nothing.
    LaunchedEffect(Unit) {
        val before = layoutGraphRepo.value()
        // A module that is off, broken or gone still owns its kinds until somebody
        // forgets it, so its widgets are not pruned as if they had been renamed away.
        val defaultKinds = DefaultLayout.load().walkInstances().map { it.kind }.toSet() + widgetModules.knownKinds()
        val result = WidgetGraphReconciler.reconcile(
            graph        = before,
            registry     = widgetRegistry,
            defaultKinds = defaultKinds,
            // Prune removed kinds only when a schema bump actually happened --
            // a deliberate app update is the safe moment to reap orphans.
            //
            // A kind vanishes here either because it was renamed away, or because
            // the module that carried it is off, broken or gone. The reconciler
            // cannot tell those apart, which is why every kind a remembered module
            // ever brought is counted as known above: only a kind no module claims
            // is reaped, and a module's kinds are let go only when somebody forgets
            // the module.
            prune        = layoutGraphRepo.migratedFromSchema != null,
        )
        if (result.graph != before) {
            val reconcileLog = LoggerFactory.getLogger("Main")
            if (result.seededSlots > 0)
                reconcileLog.info("Layout reconcile: seeded {} declared container child slot(s)", result.seededSlots)
            if (result.prunedWidgets > 0)
                reconcileLog.info("Layout reconcile: pruned {} widget(s) of removed kinds after a schema bump", result.prunedWidgets)
            layoutGraphRepo.update { result.graph }
        }
    }

    // Collapsed to the changes this shell acts on. Preparing and downloading emit
    // per chunk and per verified file, and the shell reads neither the stage nor the
    // progress, so a thousand-file install recomposed the root a few thousand times
    // and re-ran everything keyed on it.
    val launchState by remember(controller) {
        controller.state.distinctUntilChanged { old, new -> old.isPreparing() && new.isPreparing() }
    }.collectAsState(initial = controller.state.value)

    // Drains the controller's event channel into the console pane with
    // localized text. Lives at this level (not Dashboard) so events fire
    // regardless of which screen the user is currently viewing; the
    // collector is the seam that lets LauncherController stay free of
    // `client-ui` types (i18n, console). See `LaunchLogCollector` for the
    // event-to-string mapping.
    hivens.ui.logic.LaunchLogCollector(events = controller.events, gameConsole = gameConsole)


    // Persist "this account answers to a second factor" the first time a launch runs
    // into the gate. The flag is what stops later launches from logging in again, and
    // a login invalidates the session the user unlocked with a code -- so a session
    // restored from disk (written before the flag existed) would otherwise keep the
    // launcher re-authenticating and breaking itself.
    val accountStore: AccountStore = koinInject()
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            if (event !is LaunchLogEvent.TwoFactorDetected) return@collect
            runCatching {
                withContext(Dispatchers.IO) {
                    accountStore.markTwoFactor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
                }
            }.onSuccess {
                ActionRing.record("Launch met the second factor: the SmartyCraft account is marked")
            }
        }
    }


    // Bumped each time the .show signal fires; the Window content uses it
    // to invoke window.toFront() / requestFocus() so a duplicate-launch
    // attempt actually raises the existing instance, not just makes it
    // visible-but-buried-under-other-windows.
    var raiseTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val showFile = boot.paths.dataDir.resolve(".show").toFile()
        while (true) {
            delay(500.milliseconds)
            // The check and the delete off the UI thread: twice a second for the
            // life of the process, and a stall per tick on a data directory that is
            // network-mounted or asleep.
            val signalled = withContext(Dispatchers.IO) { showFile.exists().also { if (it) showFile.delete() } }
            if (signalled) {
                revealWindow()
                raiseTick++
            }
        }
    }

    // Getting out of the way once the game is up is the shell's move, not a
    // screen's: the classic dashboard widget that used to own it is composed for
    // one launch path, so a pack started from the Library or a pack page -- or a
    // relaunch driven from a notification -- left the launcher sitting in front
    // of the game.
    val postLaunch = remember {
        PostLaunchGate(runningAtMount = (controller.state.value as? LaunchState.GameRunning)?.handle)
    }

    // What the tray tooltip names: the session that is actually running. The
    // registration lands from the launch driver, so the effect keys on it too and
    // the name settles a moment after the state.
    val activeSessions by sessions.active.collectAsState()

    LaunchedEffect(launchState, activeSessions) {
        val runningName = activeSessions.values.firstOrNull()?.packDisplayName
        when (launchState) {
            is LaunchState.GameRunning, is LaunchState.Stopping -> tray.setGameStatus(true, runningName)
            is LaunchState.Error -> {
                tray.setGameStatus(false)
                if (!isWindowVisible) {
                    SwingUtilities.invokeLater { isWindowVisible = true }
                }
            }
            else -> tray.setGameStatus(false)
        }
        val move = postLaunch.onState(
            state = launchState,
            // Read where it is acted on: Downloading re-keys this effect on every
            // progress tick, and getSettings takes the lock a save holds across a
            // file write.
            hideAfterStart = launchState is LaunchState.GameRunning &&
                settingsService.getSettings().closeAfterStart,
            // isSupported, not canBeReady: hiding into a tray that is still
            // settling -- or never comes up at all -- leaves nothing to click.
            // The close button prefers canBeReady because its alternative is
            // quitting the launcher; here the alternative is the taskbar.
            trayReady       = tray.isSupported,
            windowMinimized = windowStatus.minimized,
        )
        // Recorded, because the decision is otherwise invisible: "the launcher did
        // not hide" and "it hid into a tray that is not there" and "it asked the
        // compositor to minimize and was ignored" look identical from the outside,
        // and the action log is what a diagnostic bundle carries.
        when (move) {
            PostLaunchMove.Stay       -> Unit
            PostLaunchMove.HideToTray -> {
                ActionRing.record("Game started: hiding the launcher to the tray")
                // Also to the log file: the action ring lives in memory and reaches a
                // reader only through a diagnostic bundle, which is a lot to collect
                // for one line while chasing "it did not hide".
                LoggerFactory.getLogger("PostLaunch").info("Game running: hiding the launcher to the tray")
                SwingUtilities.invokeLater { isWindowVisible = false }
            }
            PostLaunchMove.Minimize   -> {
                ActionRing.record("Game started: no tray icon is up, minimizing the window instead")
                LoggerFactory.getLogger("PostLaunch").info("Game running: no tray icon, minimizing the window")
                windowHost.minimize()
            }
            PostLaunchMove.Restore    -> windowHost.unminimize()
        }
    }

    // April Fools subsystem: provide the resolved lifecycle (Real or NoOp,
    // chosen by `AprilFoolsLoader`'s SPI scan) to every downstream
    // Composable. Wrapping at this level means tray/window close handlers
    // can capture `af` from the enclosing scope and still see the real
    // chaos flag in dev builds, while production binaries (no service
    // descriptor on the classpath) get NoOpAprilFools with zero chaos
    // overhead.
    val af = AprilFoolsLoader.instance

    CompositionLocalProvider(LocalAprilFools provides af) {
    LocaleProvider(locale = currentLocale) {
        val s = LocalStrings.current

        // Which face draws the interface. Roboto Flex is subset to Latin,
        // Cyrillic and Greek, so a locale outside those has to be drawn by the
        // bundled CJK face or it leaves the bundle for whatever the host has --
        // which on a machine without a CJK font is boxes. The question is asked
        // of the locale's own strings rather than of a list of language tags, so
        // the next locale that needs this is picked up without touching a list
        // here. A handful of structural labels is enough: if the interface is in
        // that language at all, they are in it too.
        val uiNeedsCjk = needsCjkFace(s.settingsTitle + s.navLibrary + s.aboutTitle)
        val uiFamily = if (uiNeedsCjk) nexiraCjkFamily() else null

        // Came back from a crash restart: surface a one-shot notice so the reload
        // -- which resets the current screen -- is not silent. consumeRecovered()
        // is one-shot, so a normal start stays quiet.
        // Composing is not evidence of anything: the shell mounts under the
        // still-opaque threshold, and a render-path crash happens after this
        // point by construction. Staying up is the evidence, so the crash guard
        // hears about it only once the session has lasted.
        //
        // The launcher's own updater waits on the same evidence. A build that has
        // stayed up this long is kept, and its predecessor's backup goes; one that
        // dies before it is put back. In this composition's own effect, so a crash
        // reload in between cancels the confirmation with it.
        val updateApplicator: IUpdateApplicator = koinInject()
        LaunchedEffect(Unit) {
            delay(UiRecoverySignal.HEALTHY_SESSION_MS.milliseconds)
            UiRecoverySignal.noteShellHealthy()
            withContext(Dispatchers.IO) {
                runCatching { updateApplicator.confirmStarted() }
                    .onFailure { LoggerFactory.getLogger("AppShell").warn("Could not confirm the launcher update", it) }
            }
        }

        val notificationCenter: NotificationCenter = koinInject()
        LaunchedEffect(Unit) {
            if (UiRecoverySignal.consumeRecovered()) {
                notificationCenter.push(
                    sourceKey = "ui-recovery",
                    sender    = Branding.TITLE,
                    iconUrl   = null,
                    severity  = Severity.Warn,
                    kind      = Kind.OneShot,
                    title     = s.recoveryReloadedNotice,
                )
            }
        }

        // A store written by a newer build opens read-only, which is right -- this
        // build cannot represent everything in it and must not write it back. The
        // session goes on accepting edits regardless, so say once that they will
        // not survive it. Sticky, not one-shot: a notice about work being lost
        // must not age out before the work is done. Keyed, so a shell reload after
        // a crash updates the same entry instead of stacking another.
        LaunchedEffect(Unit) {
            val affected = NewerBuildData.affectedWithReason()
            if (affected.isEmpty()) return@LaunchedEffect
            // One notice per reason, because the two say opposite things about
            // what to do: a newer file is fixed by updating, and an older one is
            // fixed by going back. A single sentence covering both would be wrong
            // for whichever half the reader has.
            affected.entries.groupBy({ it.value }, { it.key }).forEach { (reason, stores) ->
                val named = stores.joinToString(", ") { store ->
                    when (store) {
                        ReadOnlyStore.PackLibrary -> s.readOnlyDataLibrary
                        ReadOnlyStore.Layout      -> s.readOnlyDataLayout
                        ReadOnlyStore.Theme       -> s.readOnlyDataTheme
                        ReadOnlyStore.Accounts    -> s.readOnlyDataAccounts
                    }
                }
                notificationCenter.push(
                    sourceKey = "storage-read-only-${reason.name.lowercase()}",
                    sender    = Branding.TITLE,
                    iconUrl   = null,
                    severity  = Severity.Warn,
                    kind      = Kind.Sticky,
                    title     = s.readOnlyDataTitle,
                    body      = when (reason) {
                        ReadOnlyReason.NewerBuild       -> s.readOnlyDataBody(named)
                        ReadOnlyReason.UnreadableFormat -> s.readOnlyDataBodyOldFormat(named)
                        ReadOnlyReason.Damaged          -> s.readOnlyDataBodyDamaged(named)
                    },
                )
            }
        }

        // A widget module crashed the interface and the recovery switched it off.
        // Said once the shell is back, because the person has to know why the
        // widgets went and where to switch the module back on.
        val crashNotice by widgetModules.crashNotice.collectAsState()
        LaunchedEffect(crashNotice) {
            val notice = crashNotice ?: return@LaunchedEffect
            notificationCenter.push(
                sourceKey = "widget-module-crash",
                sender    = Branding.TITLE,
                iconUrl   = null,
                severity  = Severity.Warn,
                kind      = Kind.Sticky,
                title     = s.moduleCrashedTitle(notice.name),
                body      = s.moduleCrashedBody(notice.failure),
            )
            widgetModules.consumeCrashNotice()
        }

        val dataDirectory: java.nio.file.Path = koinInject()
        // What the retired SmartyCraft server path left under clients/. Announced
        // once per session and only while it is there: the files are the player's,
        // so the launcher says what it found and offers to help, and touches
        // nothing until asked. The check lists one directory and stops at the first
        // entry -- measuring gigabytes is the surface's job, when it is opened.
        val retiredClients: RetiredClientScanner = koinInject()
        val retiredGate: RetiredClientsGate = koinInject()
        LaunchedEffect(Unit) {
            val count = withContext(Dispatchers.IO) {
                if (retiredClients.anyLeftBehind()) retiredClients.count() else 0
            }
            if (count == 0) return@LaunchedEffect
            notificationCenter.push(
                sourceKey = "retired-clients",
                sender    = Branding.TITLE,
                iconUrl   = null,
                severity  = Severity.Info,
                kind      = Kind.Sticky,
                title     = s.retiredTitle,
                body      = s.retiredNoticeBody(count),
                actions   = listOf(
                    hivens.ui.notifications.NotifAction(
                        id = "retired-clients-open",
                        label = s.retiredNoticeAction,
                        onClick = { retiredGate.show() },
                    ),
                ),
            )
        }
        val packAutoUpdateService: PackAutoUpdateService = koinInject()
        val applyRecovery: ApplyRecovery = koinInject()
        // The stored library with the themes the loaded modules bring, which are never stored.
        val themeLibrary by appearance.library.collectAsState()

        // Customization extension: persisted choices for whether surfaces blur
        // and how the nav rail draws its selection. Provided via
        // [LocalCustomization] so NxTheme and the surfaces can read them
        // without prop-drilling.
        // coerceInputValues is the half that was missing, and it is the half that
        // matters: every field of the record already has a default, so an unknown
        // KEY was survivable, while an unknown VALUE was not. A release adding one
        // variant to the rail's selection enum made an older build fail the whole
        // record, fall back to defaults, and write those defaults back on the next
        // toggle. Coercion turns that into one field taking its default.
        val customizationJson    = remember {
            Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        }
        val customizationManager = remember { CustomizationManager(dataDirectory, customizationJson, AtomicFiles::writeString) }
        var customization        by remember { mutableStateOf(customizationManager.load()) }

        // Localized tray labels, derived from the active locale's strings.
        // Strings is a data class, so its structural equality lets the
        // locale-reactive effect below re-fire only when a label actually
        // changes -- not on every unrelated recomposition.
        val trayLabels = TrayStrings(
            statusIdle    = s.trayStatusIdle,
            statusRunning = s.trayStatusRunning,
            show          = s.trayShow,
            console       = s.trayConsole,
            exit          = s.trayExit,
        )

        // ── Bring-up: tray, notifier, background services (run once) ──
        // The sequence itself lives in ShellStartup, outside composition and
        // over functions rather than the singletons, so its order -- which is
        // load-bearing -- can be verified. This site only wires the real ones in.
        LaunchedEffect(Unit) {
            ShellStartup(
                policy = StartupPolicy(
                    trayEnabled         = ModuleId.Tray.id   !in settings.disabledModules,
                    notifierEnabled     = ModuleId.Notify.id !in settings.disabledModules,
                    autoUpdatePacks     = settings.autoUpdatePacks,
                ),
                bringUpTray     = { icon ->
                    withContext(Dispatchers.IO) {
                        tray.init(iconStream = icon.inputStream(), strings = trayLabels, appName = Branding.TITLE)
                    }
                },
                bringUpNotifier = { icon ->
                    withContext(Dispatchers.IO) {
                        SystemNotifier.init(appName = Branding.TITLE, appId = NEXIRA_APP_ID, iconBytes = icon)
                    }
                },
                // Off the composition dispatcher: Res.readBytes is suspend but never
                // dispatches, so reading it here would inflate a jar entry on the EDT.
                readIcon        = { path -> withContext(Dispatchers.IO) { Res.readBytes(path) } },
                trayIsSupported = { tray.isSupported },
                showWindow      = revealWindow,
                recoverInterrupted  = { applyRecovery.recoverInterrupted() },
                autoUpdatePacks     = { packAutoUpdateService.runOnce() },
                appScope            = applicationScope,
            ).run(windowVisible = { isWindowVisible })
        }

        // ── Tray / notifier callbacks (run once) ──────────────────────
        // Every captured reference is a stable singleton or remembered state,
        // so a Unit key is correct -- the assignments need to happen exactly
        // once, not on every recomposition.
        LaunchedEffect(Unit) {
            tray.onShowWindow = {
                SwingUtilities.invokeLater { revealWindow() }
            }

            // libnotify fires on its own thread, so hop to the AWT thread
            // before touching window state.
            SystemNotifier.onShowWindow = {
                SwingUtilities.invokeLater { revealWindow() }
            }

            tray.onExit = {
                SwingUtilities.invokeLater {
                    // Real impl pops the chaos close-dialog (during April Fools
                    // window); NoOp invokes onActualClose synchronously. The
                    // visibility flip is unconditional during chaos so the
                    // dialog isn't hidden behind a minimized window.
                    if (af.isActive()) isWindowVisible = true
                    af.requestCloseDialog { quit() }
                }
            }

            tray.onShowConsole = {
                SwingUtilities.invokeLater { gameConsole.show() }
            }
        }

        // ── Locale-reactive tray labels ───────────────────────────────
        // init() captures the first locale's labels and no-ops afterwards;
        // this republishes them when the user switches language at runtime so
        // the tray menu + tooltip don't stay stuck in the startup locale.
        LaunchedEffect(trayLabels) {
            tray.updateStrings(trayLabels)
        }

        // First-time-only OS notification when the window hides to the tray:
        // a desktop banner (visible while the window is gone) so the user
        // knows the launcher is still running, not closed. isWindowVisible
        // only ever goes false via a tray-hide path, so the visible -> hidden
        // transition is the trigger; it fires once ever, then persists the
        // suppression flag. Posting + the disk save run off the UI thread.
        LaunchedEffect(isWindowVisible) {
            if (isWindowVisible || !SystemNotifier.isSupported) return@LaunchedEffect
            if (settingsService.getSettings().trayHintShown) return@LaunchedEffect
            val posted = withContext(Dispatchers.IO) {
                SystemNotifier.notifyTrayHint(
                    title     = s.trayHintTitle,
                    body      = s.trayHintBody,
                    showLabel = s.trayHintShow,
                )
            }
            if (posted) withContext(Dispatchers.IO) {
                settingsService.updateSettings { it.copy(trayHintShown = true) }
            }
        }

        // The console is a window of its own beside this one (see ShellHost) and
        // draws in what this shell decided, published for it here.
        SideEffect {
            look.update(
                theme         = themeLibrary.active,
                dark          = isDarkTheme,
                locale        = currentLocale,
                uiFamily      = uiFamily,
                customization = customization,
            )
        }

        // ── Main window ────────────────────────────────────────────────
        // af.requestCloseDialog dispatches: chaos active -> pop the torturous
        // dialog; chaos inactive -> the close path we'd have taken anyway
        // (tray-hide if available, else exit). Hoisted so the OS close request
        // AND the custom caption Close button (LocalChromeClose) share it --
        // undecorated chrome must keep the same tray-hide / chaos behavior.
        val onCloseChrome: () -> Unit = {
            af.requestCloseDialog {
                if (tray.canBeReady) {
                    // canBeReady (not isSupported) so we don't kill the launcher
                    // mid-init while the tray library is still settling D-Bus /
                    // SNI handshake. If it ultimately fails, the user can quit via
                    // tray (when it appears) or kill the process -- strictly
                    // better than exiting on a close request the user clearly
                    // meant as "minimize".
                    isWindowVisible = false
                } else {
                    quit()
                }
            }
        }
        // Window-level callbacks, late-bound: the window itself is created
        // pre-Koin in ShellHost, so the real handlers register here once the
        // shell mounts. Plain assignments -- the window reads the hooks at
        // event time, and recomposition keeps them pointing at fresh captures.
        chrome.onCloseRequest = onCloseChrome
        // The main window as the host has it: what its WindowHost reads, every window
        // command, its own close path, and a raise that also brings it back from the
        // tray, the same two steps a second launch takes.
        val closeMain by rememberUpdatedState(onCloseChrome)
        val backendScope = rememberCoroutineScope()
        val mainBackend = remember(windowHost) {
            DesktopWindowBackend(
                host     = MutableStateFlow(windowHost),
                scope    = backendScope,
                commands = WindowCommand.entries.toSet(),
                onClose  = { closeMain() },
                onRaise  = {
                    revealWindow()
                    raiseTick++
                },
            )
        }
        // The window's layers and its dismiss stack, mounted at the root of the
        // window's level below. Escape that nothing focused used becomes back, which
        // a Compose popup or dialog opened later takes before the stack does. The two
        // hooks only keep a held Escape from closing one thing per repeat.
        val windowLayers = rememberWindowLayers()
        chrome.onPreviewKey = { ev ->
            windowLayers.dismiss.onPreviewKeyEvent(ev)
            // Window-scoped chords (preview = before focus dispatch) so they fire
            // no matter which composable holds focus -- the side rails own focus,
            // so a host Box-level handler misses them. resolveShellChord decides
            // what was pressed and whether to swallow it; the observers of these
            // signals gate on their own state (EditorSurfaceHost on the surface
            // being editable, ShellRightRegion on the rail).
            val resolved = resolveShellChord(ev, debugOverlay.available, editModeController.isEditing)
            when (resolved.chord) {
                ShellChord.ToggleEditMode     -> editModeController.requestEditToggle()
                ShellChord.ToggleRightRail    -> editModeController.requestRightRailToggle()
                ShellChord.ToggleDebugOverlay -> debugOverlay.toggle()
                ShellChord.UndoEdit           -> editModeController.undo()
                ShellChord.RedoEdit           -> editModeController.redo()
                null                          -> Unit
            }
            resolved.consume
        }
        chrome.onKey = windowLayers.dismiss::onKeyEvent
        run {
            // Pulled-forward: triggered by the .show watcher above when a
            // second instance fires its signal. Skip on raiseTick == 0 so
            // the first composition doesn't steal focus from whatever the
            // user was doing when the launcher started.
            LaunchedEffect(raiseTick) {
                if (raiseTick == 0) return@LaunchedEffect
                SwingUtilities.invokeLater { windowHost.raise() }
            }

            // Remembered: observe() hands out a new flow per call, and collecting a new
            // one cancels the collector and starts it again on every recomposition.
            val layoutGraph by remember(layoutGraphRepo) { layoutGraphRepo.observe() }.collectAsState()
            // The registry as it is now. A module switched on or off, or the folder
            // read again, is a new value here and a whole-tree recomposition, which
            // is what a change to the set of widgets that exist is.
            val modules by widgetModules.state.collectAsState()
            // Production renderer for a widget's own surface, see
            // [hivens.ui.widgets.WidgetSurface]. Invoked by the kernel only when a
            // widget carries one, so a widget without a plane pays nothing.
            // Remembered so its identity stays stable across AppShell recomposes:
            // it is provided through the *static* LocalWidgetSurfaceRenderer, and a
            // fresh identity each recompose would invalidate the whole content
            // subtree rather than just the widgets that have a surface.
            val surfaceRenderer: WidgetSurfaceRenderer = remember { { spec, content -> WidgetSurface(spec, content) } }
            val windowFrame = remember(window, windowHost, onCloseChrome) {
                DesktopWindowFrame(window, windowHost, windowCapabilities, onCloseChrome)
            }
            CompositionLocalProvider(
                LocalCustomization                       provides customization,
                LocalLayoutGraph                         provides layoutGraph,
                LocalWidgetRegistry                      provides modules.registry,
                LocalWidgetServiceRegistry               provides widgetServiceRegistry,
                LocalWidgetDataRegistry                  provides widgetDataRegistry,
                LocalScope                               provides appLevel,
                LocalWidgetCommandRegistry               provides widgetCommandRegistry,
                LocalWidgetStateHost                     provides widgetStateStore,
                // Dev UI-debug seams: report-only bounds instrumentation, mounted
                // ONLY while a non-release build has the overlay on AND a facet needs
                // it -- else identity, so a dev build with the overlay off runs the
                // exact release tree (and the perf HUD then measures a clean UI).
                // EditorSurfaceHost chains through these when not editing.
                LocalWidgetDecorator                     provides
                    if (debugOverlay.available && debugOverlay.enabled && debugOverlay.needsDecorators)
                        debugOverlay.widgetDecorator else IdentityWidgetDecorator,
                LocalSlotChromeModifier                  provides
                    if (debugOverlay.available && debugOverlay.enabled && debugOverlay.needsDecorators)
                        debugOverlay.slotChrome else IdentitySlotChromeModifier,
                LocalWidgetSurfaceRenderer               provides surfaceRenderer,
                LocalWidgetEntrance                      provides PlayedWidgetEntrance,
                LocalViewportScrollbar                   provides NxViewportScrollbar,
                LocalMapControls                         provides NxMapControls,
                LocalWindowState                         provides windowState,
                LocalWindowHost                          provides windowHost,
                LocalComposeWindow                       provides window,
                LocalChromeClose                         provides onCloseChrome,
                LocalWindowHide                          provides { isWindowVisible = false },
                LocalUseCustomChrome                     provides settings.useCustomChrome,
                LocalWindowFrame                         provides windowFrame,
                LocalWindowCapabilities                  provides windowCapabilities.value,
                LocalCustomChromeAvailable               provides WindowManagerProbe.allowsCustomFrame,
            ) {

            // The main window in the host's registry, and its level, for as long as
            // this shell draws it. The console is a window of its own beside it.
            HostedWindow(WindowId.Main, WindowOwner.Launcher, mainBackend, windowRegistry) {
            // A notification action opens here, in the main window, and raises it.
            MainWindowNavigation(raise = mainBackend::raise)
            LayerHost(windowLayers, Modifier.fillMaxSize()) {
            val themeReveal = rememberThemeReveal()
            NxTheme(
                theme    = themeLibrary.active,
                dark     = isDarkTheme,
                uiFamily = uiFamily,
            ) {
                // What shows where Skia has not drawn yet while the window grows:
                // the page colour, so a resize shows the theme and not light grey.
                val page = NxColor.page
                SideEffect { window.fillUnpainted(AwtColor(page.toArgb(), false)) }
                ThemeRevealHost(themeReveal) {
                val migration = boot.pendingMigration
                if (migration != null) {
                    // Migration is mandatory: the screen does not return
                    // to AppRoot on completion. The user clicks Quit and
                    // relaunches; the next process sees the .migrated
                    // marker and skips this branch. Local capture so
                    // the smart cast survives the nested MigrationScreen
                    // call -- boot.pendingMigration is a public property
                    // declared in client-launcher, and Kotlin's smart-cast
                    // doesn't extend across module boundaries.
                    MigrationScreen(
                        source = migration,
                        target = boot.paths.dataDir,
                        onQuit = exitApp,
                    )
                } else {
                    AppRoot(
                        onRealExit   = quit,
                        onHideToTray = if (tray.canBeReady) {{ isWindowVisible = false }}
                        else null,
                        customization              = customization,
                        onCustomizationChanged     = { newCustomization ->
                            customization = newCustomization
                            preferences.write("the customization") { customizationManager.save(newCustomization) }
                        },
                    )
                    UpdateManager()
                }
                } // end ThemeRevealHost
            }
            // Dev UI-debug overlay: top of the shell Box z-order (above AppRoot and
            // NotificationStack), its own NxTheme wrap so it draws in the active theme.
            // Inert unless a non-release build has the master toggle on.
            NxTheme(
                theme    = themeLibrary.active,
                dark     = isDarkTheme,
                uiFamily = uiFamily,
            ) {
                DebugOverlay(debugOverlay)
                // Inside the theme on purpose: the prompts are dialogs on the window's
                // layers, which take their composition locals from where they are
                // opened, and one opened outside the theme finds none and takes the
                // shell down.
                hivens.ui.components.TwoFactorPromptHost()
                // Whatever read the host -- the news, a login -- parks its refused
                // certificate here for the user to answer once.
                hivens.ui.components.CertificatePromptHost()
                // What the retired server path left on disk, when the player asks
                // the reminder to show them.
                hivens.ui.legacy.RetiredClientsHost()
                // The question a quit asks when a game is running. Both answers record
                // the session before the process goes; stopping waits for the game's
                // own shutdown, bounded so a game that will not go cannot hold the
                // launcher open.
                QuitWithGameHost(
                    gate = quitGate,
                    packName = activeSessions.values.firstOrNull()?.packDisplayName,
                    onLeaveRunning = {
                        applicationScope.launch {
                            controller.settleSessionForQuit()
                            SwingUtilities.invokeLater { exitApp() }
                        }
                    },
                    onStopGame = {
                        applicationScope.launch {
                            controller.abort()
                            withTimeoutOrNull(QUIT_STOP_WAIT) {
                                controller.state.first { it is LaunchState.Idle || it is LaunchState.Error }
                            }
                            SwingUtilities.invokeLater { exitApp() }
                        }
                    },
                )
            }
            // Synthetic resize grips -- undecorated drops the native border. Only
            // with custom chrome (else the OS frame resizes); self-gates to
            // Floating, to a window manager that allows resizing, and is
            // transparent, so it's otherwise harmless.
            if (settings.useCustomChrome) {
                WindowResizeHandles(
                    window    = window,
                    host      = windowHost,
                    maximized = windowStatus.maximized,
                    canResize = windowCapabilities.value.resize && !windowHost.nativeFrame,
                )
            }
            } // end LayerHost
            } // end HostedWindow(Main)
            } // end CompositionLocalProvider(LocalCustomization)
        }
    }
    } // end CompositionLocalProvider(LocalAprilFools)
}

/** Getting a launch ready: the two states that change with every chunk and every file. */
private fun LaunchState.isPreparing(): Boolean = this is LaunchState.Prepare || this is LaunchState.Downloading

/**
 * How long quitting waits for a game it was asked to stop. The game gets its own
 * termination grace and then a forced kill; this only has to outlast both.
 */
private val QUIT_STOP_WAIT = 15.seconds
