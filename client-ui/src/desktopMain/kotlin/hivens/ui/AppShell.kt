package hivens.ui

import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import hivens.ui.components.QuitWithGameHost
import hivens.ui.components.QuitGate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import hivens.ui.render.SvgImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import hivens.config.Branding
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.RefreshableAuthProvider
import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyReason
import hivens.core.data.ReadOnlyStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ModuleId
import hivens.core.data.PackAuthRequirement
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.core.launch.LaunchLogEvent
import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.core.data.darkThemeFor
import hivens.core.data.resolveInitialThemeMode
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.update.ApplyRecovery
import hivens.launcher.update.PackAutoUpdateService
import hivens.core.diag.ActionRing
import hivens.core.security.SslBypassStore
import hivens.launcher.bootstrap.AutoLoginCoordinator
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
import hivens.ui.diag.SkinemaGate
import hivens.ui.diag.UiRecoverySignal
import hivens.auth.AccountStore
import hivens.core.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.chrome.ExtraButton
import hivens.ui.chrome.awtOnX11
import hivens.ui.chrome.computeSafeWindowMinSize
import hivens.ui.chrome.extraButton
import hivens.ui.chrome.resendAsSidewaysWheel
import hivens.tray.TrayController
import hivens.tray.TrayStrings
import hivens.ui.background.BackgroundManager
import hivens.ui.background.CustomBackground
import hivens.ui.theme.WallpaperTone
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.isFrom
import hivens.ui.chrome.LocalWindowHide
import hivens.ui.chrome.ShellChord
import hivens.ui.chrome.resolveShellChord
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowMaximizer
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.WindowMaximizer
import hivens.ui.chrome.WindowResizeHandles
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.UpdateManager
import hivens.core.io.AtomicFiles
import hivens.ui.customization.CustomizationManager
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.LocalCustomization
import hivens.ui.easter.AprilFools
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.editor.EditModeController
import hivens.ui.editor.WidgetGraphReconciler
import hivens.ui.generated.resources.Res
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.puppet.PuppetClick
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.screens.ConsoleWindow
import hivens.ui.screens.MigrationScreen
import hivens.ui.theme.NxTheme
import hivens.ui.text.needsCjkFace
import hivens.ui.theme.nexiraCjkFamily
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.SystemTheme
import hivens.ui.theme.ThemeRevealHost
import hivens.ui.theme.rememberThemeReveal
import hivens.ui.theme.ThemeManager
import hivens.ui.system.SystemNotifier
import hivens.ui.utils.GameConsoleService
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import hivens.ui.widgets.WidgetSurface
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.ui.screens.mod.ModTarget
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import org.jetbrains.compose.resources.ExperimentalResourceApi
import hivens.ui.navigation.NavRequests
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import hivens.ui.theme.NxColor

// 2-column Library + sidebar starts collapsing visibly below this width;
// 600dp of height keeps PackDetail hero + sidebar both reachable. Held
// as file-level consts so the prophylactic min-size effect inside
// AppShell stays a one-liner.
private const val MIN_WINDOW_WIDTH_DP  = 960
private const val MIN_WINDOW_HEIGHT_DP = 600

// Windows Application User Model ID for toast routing (libnotify / SystemNotifier).
// Matches the macOS bundleID for a single cross-platform identity; ignored on
// Linux. A toast needs this AUMID registered (a Start-menu shortcut carrying
// it), which a plain install may lack -- the tray hint then no-ops on Windows,
// by design, while Linux (the primary desktop) shows it.
private const val NEXIRA_APP_ID = "dev.hivens.nexira"

// ─── State ───────────────────────────────────────────────────────────────────

sealed class AppState {
    object Loading : AppState()
    object Unauthenticated : AppState()
    data class Authenticated(val session: SessionData) : AppState()
}

// ─── Navigation ──────────────────────────────────────────────────────────────

sealed class Screen {
    object Home               : Screen()
    object Library            : Screen()
    object Browse             : Screen()
    object Profile            : Screen()
    object Wardrobe           : Screen()
    object Settings           : Screen()
    object ThemePicker        : Screen()
    object About              : Screen()
    object BackgroundSettings : Screen()

    /**
     * Library card click target. Carries the PackInstance UUID; the
     * detail screen resolves it via [hivens.core.api.interfaces.IPackRepository]
     * so the Screen sealed class stays free of domain types and the
     * back-stack item stays small (a UUID string, not a PackInstance
     * graph). [openSettings] restores the settings overlay on arrival --
     * stamped onto the back-stack entry when the user drills from the
     * settings window into the versions screen, so Back lands them in
     * the settings they left, not on the bare pack page.
     */
    data class PackDetail    (
        val instanceId: String,
        val openSettings: Boolean = false,
        /**
         * Which section the settings panel reopens on. Null means the one it
         * opens on by default; leaving the version screen names Version, because
         * that is where the user was standing when they left.
         */
        val settingsSection: PackSettingsCategory? = null,
    ) : Screen()

    /**
     * Version manager for an installed mirror pack: the retained build list,
     * per-build changelog (client-side manifest diff), switch/rollback and
     * restore points. Same UUID-only payload rationale as [PackDetail].
     */
    data class PackVersions  (val instanceId: String) : Screen()

    /**
     * Catalogue-side detail target, source-neutral: carries the [origin] + that
     * source's local pack id. The one [hivens.ui.screens.browse.CataloguePackDetailScreen]
     * resolves both through [hivens.launcher.catalogue.PackCatalogueRegistry] +
     * [hivens.launcher.PackInstallCoordinator]. Distinct from [PackDetail], which
     * resolves an already-installed [hivens.core.data.PackInstance].
     */
    data class CataloguePackDetail(val origin: PackOrigin, val packId: String) : Screen()

    /**
     * The project page (#367), rendered natively rather than linked out.
     *
     * A SCREEN and not a panel, because its metadata blocks live in
     * `appshell.rightrail` -- a shell surface present on every screen -- and a
     * page carrying its own right-hand column would stand a third column beside
     * the news. Opening one therefore navigates; it cannot open in place, because
     * in place the blocks have nowhere to appear.
     *
     * Carries a [ModTarget] rather than a project id so a jar the catalogue has
     * never indexed gets the same page as one it has.
     */
    data class ModDetail(val target: ModTarget) : Screen()

    /**
     * One build of a project: what it runs on, what it needs, what changed and
     * which files it ships.
     *
     * The versions table answers "which builds exist" and cannot also answer
     * "what is this one", because the second question needs a column per
     * dependency and a paragraph of notes. It carries the [target] as well as the
     * build so the rail keeps describing the project the build belongs to, and so
     * an install from here knows which pack it is installing into.
     *
     * [versionNumber] rides along because the caller always has it and the
     * breadcrumb needs it on the first frame. Resolving it would label the crumb
     * with a catalogue id for as long as the fetch took, which is the one name
     * nobody clicked.
     */
    data class ModVersion(
        val target: ModTarget,
        val versionId: String,
        val versionNumber: String,
    ) : Screen()

    /**
     * A screen somebody made, by its id. Everything about it, what it is called and
     * what it holds, is in the layout graph, so this carries the id and nothing else
     * and a rename never leaves a stale title on the back stack.
     */
    data class Custom(val id: String) : Screen()

    /**
     * Identity for state that outlives a visit, stable across the fields a screen
     * stamps onto its own back-stack entry.
     *
     * Not the entry itself: [PackDetail] re-describes itself with [PackDetail.openSettings]
     * before pushing the versions screen, and keying on the whole entry would throw
     * away the tab, the file tree and the scroll every time that note was taken.
     * Two visits to the same pack are the same place; two visits to different packs
     * are not, which is why the ids are in the key and nothing else is.
     */
    val retentionKey: String get() = when (this) {
        is PackDetail          -> "PackDetail:$instanceId"
        is PackVersions        -> "PackVersions:$instanceId"
        is CataloguePackDetail -> "CataloguePackDetail:$origin:$packId"
        is ModDetail           -> "ModDetail:${target.key}"
        is ModVersion          -> "ModVersion:${target.key}:$versionId"
        is Custom              -> "Custom:$id"
        else                   -> this::class.simpleName.orEmpty()
    }
}

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
    val widgetCommandRegistry: WidgetCommandRegistry = koinInject()
    val widgetStateStore: WidgetStateStore = koinInject()
    val editModeController: EditModeController  = koinInject()
    val sessions: hivens.ui.notifications.SessionRegistry = koinInject()
    // Shared process-lifetime scope (createdAtStart in appModule; canceled
    // by AppCoroutineScopeHook on JVM shutdown). Same instance backs
    // LauncherController.appScope and any other fire-and-forget work.
    val applicationScope: CoroutineScope        = koinInject()

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

    // Native maximize/restore for the undecorated window -- the WM owns the
    // geometry and reports the real maximized state back through WindowMaximizer's
    // listener; we never fake it. Detach the listener on dispose.
    val maximizer = remember { WindowMaximizer(windowState).also { it.attach(window) } }
    DisposableEffect(window) { onDispose { maximizer.detach() } }

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
        if (windowState.isMinimized) windowState.isMinimized = false
        isWindowVisible = true
    }

    var isDarkTheme   by remember { mutableStateOf(settings.isDarkTheme) }
    // Which source drives dark/light: the manual toggle, the OS scheme, or the
    // wallpaper's brightness. Both automatic sources write through isDarkTheme (and
    // persist it), so everything downstream keeps reading one boolean.
    var themeMode by remember { mutableStateOf(resolveInitialThemeMode(settings)) }
    // Whether the OS scheme is readable at all (no portal backend on Linux -> no).
    // Probed once; drives the System chip's enabled state in the theme island.
    var systemThemeAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { systemThemeAvailable = withContext(Dispatchers.IO) { SystemTheme.probe() } != null }
    var wallpaperLuminance by remember { mutableStateOf<Float?>(null) }

    // Applying an automatic source: set the flag and persist it, so the next cold
    // start opens on what was last observed instead of flashing the old scheme.
    // darkThemeFor returns null when nothing should change, which keeps a
    // wallpaper crossfade from writing the settings file on every tick.
    val applyAutomaticDark: suspend (Boolean?) -> Unit = { wanted ->
        if (wanted != null) {
            isDarkTheme = wanted
            settingsService.updateSettings { it.copy(isDarkTheme = wanted) }
        }
    }

    LaunchedEffect(themeMode, wallpaperLuminance) {
        applyAutomaticDark(darkThemeFor(themeMode, isDarkTheme, wallpaperLuminance = wallpaperLuminance))
    }
    // System mode: follow the OS scheme while the mode is active -- the cold flow
    // (portal signal on Linux, polling fallback) runs only while collected, so the
    // other modes cost nothing.
    LaunchedEffect(themeMode) {
        if (themeMode != ThemeMode.System) return@LaunchedEffect
        SystemTheme.observe().collect { dark ->
            applyAutomaticDark(darkThemeFor(themeMode, isDarkTheme, systemDark = dark))
        }
    }
    var currentLocale by remember {
        mutableStateOf(AppLocale.fromTag(settings.locale))
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
            windowMinimized = windowState.isMinimized,
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
                windowState.isMinimized = true
            }
            PostLaunchMove.Restore    -> windowState.isMinimized = false
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
        val themeManager  = remember { ThemeManager(dataDirectory, AtomicFiles::writeString) }
        var themeLibrary  by remember {
            val loaded = themeManager.load()
            // The manager decides read-only on its own, in a module that cannot see
            // the notice registry, so the fact is carried across here. This runs
            // during composition and the notice above is a LaunchedEffect, which
            // runs after it, so the ordering holds.
            if (themeManager.readOnly) NewerBuildData.record(ReadOnlyStore.Theme)
            mutableStateOf(loaded)
        }

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

        // Console window moved inside the CompositionLocalProvider /
        // NxTheme block below so it inherits the active theme and the
        // customization. The window
        // itself is a separate OS surface, but Compose Desktop propagates
        // CompositionLocals down through the Window composable.

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
        chrome.onPreviewKey = { ev ->
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
                ShellChord.ExitEditor         -> editModeController.requestEditorEscape()
                ShellChord.UndoEdit           -> editModeController.undo()
                ShellChord.RedoEdit           -> editModeController.redo()
                null                          -> Unit
            }
            resolved.consume
        }
        run {
            // Pulled-forward: triggered by the .show watcher above when a
            // second instance fires its signal. Skip on raiseTick == 0 so
            // the first composition doesn't steal focus from whatever the
            // user was doing when the launcher started.
            LaunchedEffect(raiseTick) {
                if (raiseTick == 0) return@LaunchedEffect
                SwingUtilities.invokeLater {
                    // The isAlwaysOnTop trick is the only cross-WM way to
                    // force a raise on X11 (KDE / Hyprland / GNOME all
                    // ignore plain toFront() to discourage focus-stealing).
                    // Pulse it: enable -> toFront -> requestFocus -> disable.
                    window.isAlwaysOnTop = true
                    window.toFront()
                    window.requestFocus()
                    window.isAlwaysOnTop = false
                }
            }

            // Prophylactic min-size clamped against the current display.
            // Recomputes on display crossing (not every pixel of a drag).
            // Wayland peer-init can return non-null GC with zero bounds
            // before the surface negotiates -- guard on positive size.
            val sizeDensity = LocalDensity.current
            DisposableEffect(window, sizeDensity) {
                val applyClamp: () -> Unit = {
                    val designPx = with(sizeDensity) {
                        Dimension(
                            MIN_WINDOW_WIDTH_DP.dp.toPx().toInt(),
                            MIN_WINDOW_HEIGHT_DP.dp.toPx().toInt(),
                        )
                    }
                    val gc = window.graphicsConfiguration
                    val gcBounds = gc?.bounds
                    val screen = if (gcBounds != null && gcBounds.width > 0 && gcBounds.height > 0) {
                        Dimension(gcBounds.width, gcBounds.height)
                    } else {
                        Toolkit.getDefaultToolkit().screenSize
                    }
                    val safe = computeSafeWindowMinSize(designPx.width, designPx.height, screen)
                    SwingUtilities.invokeLater { window.minimumSize = safe }
                }

                var lastDeviceId: String? = window.graphicsConfiguration?.device?.iDstring
                val moveListener = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) {
                        val current = window.graphicsConfiguration?.device?.iDstring
                        if (current != lastDeviceId) {
                            lastDeviceId = current
                            applyClamp()
                        }
                    }
                }
                window.addComponentListener(moveListener)
                applyClamp()

                onDispose {
                    window.removeComponentListener(moveListener)
                }
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
            // Which family each surface shows. From Koin, not remembered here, so a
            // switch can come from outside the composition.
            val surfaceFamilies: SurfaceFamilies = koinInject()
            CompositionLocalProvider(
                LocalCustomization                       provides customization,
                LocalLayoutGraph                         provides layoutGraph,
                LocalWidgetRegistry                      provides modules.registry,
                LocalWidgetServiceRegistry               provides widgetServiceRegistry,
                LocalWidgetDataRegistry                  provides widgetDataRegistry,
                LocalWidgetCommandRegistry               provides widgetCommandRegistry,
                LocalWidgetStateHost                     provides widgetStateStore,
                LocalSurfaceFamilies                     provides surfaceFamilies,
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
                LocalWindowMaximizer                     provides maximizer,
                LocalComposeWindow                       provides window,
                LocalChromeClose                         provides onCloseChrome,
                LocalWindowHide                          provides { isWindowVisible = false },
                LocalUseCustomChrome                     provides settings.useCustomChrome,
            ) {

            // Console runs as its own OS window but is composed from here so
            // it inherits LocalCustomization via the composition tree. Its own
            // NxTheme wrap is what projects the theme into the window's surface.
            // This site only ensures the composition locals are in scope.
            if (gameConsole.shouldShowConsole) {
                // Console preferences are the store's; the window collects them
                // itself so a slider drag does not recompose the shell.
                ConsoleWindow(
                    isDarkTheme    = isDarkTheme,
                    onClose        = { gameConsole.hide() },
                    theme          = themeLibrary.active,
                )
            }

            Box(Modifier.fillMaxSize()) {
            val themeReveal = rememberThemeReveal()
            NxTheme(
                theme    = themeLibrary.active,
                dark     = isDarkTheme,
                uiFamily = uiFamily,
            ) {
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
                        onWallpaperLuminance = { wallpaperLuminance = it },
                        onWallpaperColours   = { colours ->
                            // Stored with the selection, so the wallpaper theme opens in
                            // itself next time. Written only when the picture changed.
                            if (colours != themeLibrary.wallpaper) {
                                themeLibrary = themeLibrary.copy(wallpaper = colours)
                                themeManager.save(themeLibrary)
                            }
                        },
                        onRealExit   = quit,
                        onHideToTray = if (tray.canBeReady) {{ isWindowVisible = false }}
                        else null,
                        isDarkTheme          = isDarkTheme,
                        onToggleDarkTheme    = {
                            // An explicit flip always wins: leaving an automatic mode
                            // drops back to Manual in the same save.
                            isDarkTheme = !isDarkTheme
                            themeMode = ThemeMode.Manual
                            val dark = isDarkTheme
                            settingsService.updateSettings { it.copy(
                                isDarkTheme = dark,
                                themeMode = ThemeMode.Manual,
                                themeFromWallpaper = false,
                            ) }
                        },
                        themeMode = themeMode,
                        onThemeModeChanged = { mode ->
                            themeMode = mode
                            // themeFromWallpaper mirrors the mode so a downgrade to a
                            // pre-mode build keeps the wallpaper opt-in coherent.
                            settingsService.updateSettings { it.copy(
                                themeMode = mode,
                                themeFromWallpaper = mode == ThemeMode.Wallpaper,
                            ) }
                        },
                        systemThemeAvailable = systemThemeAvailable,
                        themeLibrary         = themeLibrary,
                        onThemeSelected      = { id ->
                            themeLibrary = themeLibrary.copy(selected = id)
                            themeManager.save(themeLibrary)
                        },
                        currentLocale   = currentLocale,
                        onLocaleChanged = { newLocale ->
                            currentLocale = newLocale
                            settingsService.updateSettings { it.copy(locale = newLocale.tag) }
                        },
                        customization              = customization,
                        onCustomizationChanged     = { newCustomization ->
                            customization = newCustomization
                            customizationManager.save(newCustomization)
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
                // Inside the theme on purpose: the prompts are Dialogs with their own
                // composition, and one raised from outside finds no theme and takes
                // the shell down.
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
            // Floating + non-tiling and is transparent, so it's otherwise harmless.
            if (settings.useCustomChrome) {
                WindowResizeHandles(
                    state     = windowState,
                    minSize   = DpSize(MIN_WINDOW_WIDTH_DP.dp, MIN_WINDOW_HEIGHT_DP.dp),
                    maximized = maximizer.maximized,
                )
            }
            } // end Box(window resize overlay)
            } // end CompositionLocalProvider(LocalCustomization)
        }
    }
    } // end CompositionLocalProvider(LocalAprilFools)
}

// ─── App Root ─────────────────────────────────────────────────────────────────

@Composable
fun AppRoot(
    onWallpaperLuminance: (Float?) -> Unit,
    onWallpaperColours: (List<Int>) -> Unit,
    isDarkTheme: Boolean,
    onRealExit: () -> Unit,
    onHideToTray: (() -> Unit)?,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    themeLibrary: ThemeLibrary,
    onThemeSelected: (String) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
) {
    val credentialsManager: AccountStore = koinInject()
    val authService: AuthProvider              = koinInject()
    val settingsService: ISettingsService      = koinInject()
    val dataDirectory: java.nio.file.Path      = koinInject()
    val json: Json                             = koinInject()
    val authRegistry: AuthProviderRegistry     = koinInject()
    val bypassStore: SslBypassStore            = koinInject()
    // Present only when a Microsoft client id is configured -- the registry holds
    // the refreshable provider exactly then, so auto-login is gated by its presence.
    val msaProvider: RefreshableAuthProvider?  =
        authRegistry.all.filterIsInstance<RefreshableAuthProvider>().firstOrNull()
    // Smartycraft-routed Call.Factory for Coil's image fetcher. The
    // bypass / direct routing rule lives in Modules.kt alongside the
    // same rule for the Ktor HttpClientProvider; both must agree or
    // news / skin images would diverge from auth and protocol traffic
    // on the same host.
    val routingCallFactory: Call.Factory       = koinInject()
    val af = LocalAprilFools.current

    // Register the routing-aware Coil loader exactly once. setSafe is
    // idempotent (it no-ops once a loader exists), but allocating the factory
    // on every AppRoot recompose is wasted work and brushes against coil's
    // "called after the first get()" guard, so gate it behind remember. The
    // calculation runs synchronously in the composition pass -- before any
    // child image composable triggers a get().
    remember(routingCallFactory) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // In the launcher's own data, not the default under the system temp
                // dir: that one is a tmpfs on many Linux machines, emptied on every
                // boot, so every icon and banner was downloaded again each day, and it
                // is shared with any other program built on the same library.
                .diskCache {
                    DiskCache.Builder()
                        .directory(dataDirectory.resolve("cache").resolve("images").toFile())
                        .maxSizeBytes(IMAGE_CACHE_BYTES)
                        .build()
                }
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { routingCallFactory }))
                    // Pack descriptions carry rows of shields.io badges, and
                    // shields.io answers with SVG.
                    add(SvgImageDecoder.Factory())
                }
                .build()
        }
    }

    var appState      by remember { mutableStateOf<AppState>(AppState.Loading) }
    // Navigation history. navigate() pushes detail screens and resets on a
    // top-level destination; back() pops to the actual previous screen instead
    // of a per-screen hardcoded return target.
    val backStack     = remember { NavBackStack(Screen.Home) }
    var pendingLogout by remember { mutableStateOf(false) }
    // Clearing every account takes the face choice with it: it names a provider
    // that no longer has one, and nothing surfaces the setting again until two
    // accounts are back, so it would decide the face of a session the user set
    // up long after making it.
    //
    // The offline name goes too, whichever identity was fronting the shell. It is
    // the only record of the offline identity, and startup signs that identity
    // back in from it when no account is stored, which after this is always: kept
    // because the face happened to be an online account, it had the next start
    // sign straight back in under a name the user had just signed out of. The
    // offline mode switch stays as it is. It says how to play rather than who
    // plays, and with no name and no account it has nobody to sign in.
    //
    // The store is cleared off the UI thread, since every secret it deletes is a
    // keyring call, and the shell signs out once it has been.
    val logoutScope = rememberCoroutineScope()
    val doLogout: () -> Unit = {
        logoutScope.launch {
            withContext(Dispatchers.IO) {
                credentialsManager.clear()
                settingsService.updateSettings { it.copy(preferredFaceProvider = null, offlinePlayerName = null) }
            }
            appState = AppState.Unauthenticated
        }
    }

    // Mouse side buttons (back/forward) -> history navigation. Compose's pointer
    // layer only surfaces primary/secondary/tertiary on this platform, so listen at
    // the AWT level where the thumb buttons still arrive. The AWT event thread is
    // the Compose UI thread in Compose Desktop, so mutating the NavBackStack here is
    // on the right thread.
    //
    // Which number is which depends on the toolkit (see extraButton): on X11 the
    // sideways wheel arrives here too, and goes back to the window as a wheel.
    //
    // A toolkit listener hears every window the process has, so history moves only
    // for a press in this one. Unfiltered, a thumb press in the separate console
    // window paged the main window's history behind it. The sideways wheel goes back
    // to whichever window it came from.
    val mainWindow by rememberUpdatedState(LocalComposeWindow.current)
    DisposableEffect(Unit) {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = AWTEventListener { ev ->
            if (ev is MouseEvent && ev.id == MouseEvent.MOUSE_PRESSED) {
                when (extraButton(ev.button, awtOnX11)) {
                    ExtraButton.Back        -> if (ev.isFrom(mainWindow)) backStack.back()
                    ExtraButton.Forward     -> if (ev.isFrom(mainWindow)) backStack.forward()
                    ExtraButton.ScrollLeft  -> resendAsSidewaysWheel(ev, toRight = false)
                    ExtraButton.ScrollRight -> resendAsSidewaysWheel(ev, toRight = true)
                    ExtraButton.None        -> Unit
                }
            }
        }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }

    // A made screen deleted while it sits in the history leaves it, back and
    // forward both, so neither arrow can open a page about nothing.
    val screenIds = LocalLayoutGraph.current.screens.map { it.id }.toSet()
    LaunchedEffect(screenIds) {
        backStack.retainWhere(Screen.Home) { it !is Screen.Custom || it.id in screenIds }
    }

    // Out-of-composition navigation requests (notification actions, drivers)
    // land in the same back stack the buttons above drive.
    val navRequests: NavRequests = koinInject()
    LaunchedEffect(navRequests) {
        navRequests.requests.collect { backStack.navigate(it) }
    }

    // ── Background settings ───────────────────────────────────────────────
    val backgroundManager: BackgroundManager = koinInject()
    val backgroundSettings by backgroundManager.settings.collectAsState()
    // Persist background settings debounced and OFF the UI thread: the fx
    // sliders fire per tick, and a synchronous write per tick both janks the
    // drag and multiplies disk writes. The effect restarts on every value
    // change (keyed), so one write lands ~300ms after the drag settles. The
    // manager flushes whatever is still unwritten at shutdown, so a quit or a
    // crash-restart inside the debounce keeps the last slider position.
    LaunchedEffect(backgroundSettings) {
        delay(300.milliseconds)
        withContext(Dispatchers.IO) { backgroundManager.flush() }
    }

    // ── Auto-login with offline mode support ──────────────────────────────
    // Business logic lives in AutoLoginCoordinator; the Composable maps the
    // resolution into the local AppState machine. Network-shaped failures
    // retry on a capped backoff for the app's lifetime (a launcher left open
    // signs itself in when the network returns); rejections and missing
    // credentials stop -- looping on those hammers the upstream for nothing.
    // A bypass policy flip restarts the effect for an immediate fresh attempt
    // with a reset ladder (the flip is a user action). A manual login racing
    // the loop wins: the loop re-reads the state each pass, and a pass whose
    // sign-in was already in flight checks it again once the answer is back.
    val autoLoginBypasses by bypassStore.bypasses.collectAsState()
    LaunchedEffect(autoLoginBypasses) {
        var attempt = 0
        while (appState !is AppState.Authenticated) {
            val settings = withContext(Dispatchers.IO) { settingsService.getSettings() }
            val saved = withContext(Dispatchers.IO) {
                credentialsManager.primarySession(settings.preferredFaceProvider)
            }
            val resolution = withContext(Dispatchers.IO) {
                AutoLoginCoordinator.resolveSession(
                    settings     = settings,
                    saved        = saved,
                    authService  = authService,
                    msaProvider  = msaProvider,
                )
            }
            when (resolution) {
                is AutoLoginCoordinator.Resolution.Success -> {
                    val session = resolution.session
                    // A silent MSA refresh rotates the refresh token; persist it so
                    // the next start uses the fresh one instead of re-spending the
                    // stored token. A rotation is not a choice of account, so it never
                    // takes the active slot: it used to, on every start, so "active"
                    // came to mean whichever account the boot happened to refresh, and
                    // a manual sign-in that won the race below lost the slot too.
                    if (session.refreshToken != null && session.refreshToken != saved?.refreshToken) {
                        withContext(Dispatchers.IO) {
                            credentialsManager.saveAccount(
                                session,
                                PackAuthRequirement.Microsoft.PROVIDER_KEY,
                                makeActive = false,
                            )
                        }
                    }
                    // The sign-in that just ran met the 2FA gate, and it is the only
                    // thing that could have told us. Unpersisted, the coordinator would
                    // spend another login on the next start -- and each one invalidates
                    // whatever session the player has in hand.
                    if (session.twoFactor && saved?.twoFactor != true) {
                        withContext(Dispatchers.IO) {
                            credentialsManager.markTwoFactor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
                        }
                        ActionRing.record("Auto-login met the second factor: the SmartyCraft account is marked")
                    }
                    // The form stays usable while a pass is in flight, so the user may
                    // have signed in by hand while this one waited on the network.
                    if (appState is AppState.Authenticated) {
                        ActionRing.record("Auto-login answered after a manual sign-in; the manual one stays")
                        return@LaunchedEffect
                    }
                    appState = AppState.Authenticated(session)
                    return@LaunchedEffect
                }
                AutoLoginCoordinator.Resolution.NoCredentials,
                AutoLoginCoordinator.Resolution.Rejected,
                // The certificate decision belongs to the user. Dropping to the
                // login form routes them to the prompt that asks for it, which
                // also grants the bypass and restores silent auto-login from the
                // next start.
                AutoLoginCoordinator.Resolution.CertificateUntrusted -> {
                    appState = AppState.Unauthenticated
                    return@LaunchedEffect
                }
                AutoLoginCoordinator.Resolution.NetworkDown -> {
                    // The startup spinner covers only the first attempt; after it
                    // the login form is usable while the loop retries silently.
                    if (appState is AppState.Loading) appState = AppState.Unauthenticated
                    val delayMs = AutoLoginCoordinator.retryDelayMs(attempt)
                    attempt += 1
                    ActionRing.record("Auto-login: network down, retry #$attempt in ${delayMs / 1000}s")
                    delay(delayMs.milliseconds)
                }
            }
        }
    }

    // ── Render: background behind layout ──────────────────────────────────
    val mousePos    = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val mousePxPos  = remember { mutableStateOf(Offset.Zero) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    // What the wallpaper tells the shell: its overall brightness, for the mode that
    // follows the wallpaper between dark and light, and its colours, which the
    // wallpaper theme is made of.
    var tone by remember { mutableStateOf(WallpaperTone.NONE) }

    LaunchedEffect(tone.avgLuminance) { onWallpaperLuminance(tone.avgLuminance) }

    Box(
        Modifier
            .fillMaxSize()
            // Base fill behind the wallpaper: while a custom background decodes (or its
            // first video frame arrives) CustomBackground paints nothing, and without
            // this the bare window default -- a flat grey -- shows through. The theme
            // surface is covered edge-to-edge once the image is ready.
            .background(NxColor.page)
            .onSizeChanged { windowSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null && windowSize.width > 0 && windowSize.height > 0) {
                                mousePxPos.value = pos
                                mousePos.value   = Offset(pos.x / windowSize.width, pos.y / windowSize.height)
                            }
                        }
                    }
                }
            }
    ) {
      CustomBackground(
          settings         = backgroundSettings,
          mousePosProvider = { mousePos.value },
          // Colours are passed on as reported and not from the initial state, which
          // would clear the stored ones on every start before the picture decoded.
          onTone           = { tone = it; onWallpaperColours(it.colours) },
          // The one setting a player widget can move while the wallpaper is what
          // its transport is pointed at. Through the same state the appearance
          // panel writes, so the two sliders are one value and the debounce above
          // persists it once.
          onAudioVolume    = { volume -> backgroundManager.update { it.copy(audioVolume = volume) } },
      )

      af.WrapContent(
          pixelCursorState = mousePxPos,
          windowSize       = windowSize,
          onRealClose      = onRealExit,
          onHideTray       = onHideToTray,
      ) {
          AppLayout(
              appState = appState,
              currentScreen = backStack.current,
              onScreenChange = backStack::navigate,
              onSwitchTab = backStack::switchTo,
              onReplaceScreen = backStack::replaceCurrent,
              onBack = { backStack.back() },
              canGoBack = backStack.canGoBack,
              canGoForward = backStack.canGoForward,
              onForward = { backStack.forward() },
              trail = backStack.trail,
              onPopTo = backStack::popTo,
              onLogin = { session -> appState = AppState.Authenticated(session) },
              onLogout = { pendingLogout = true },
              isDarkTheme = isDarkTheme,
              onToggleDarkTheme = onToggleDarkTheme,
              themeMode = themeMode,
              onThemeModeChanged = onThemeModeChanged,
              systemThemeAvailable = systemThemeAvailable,
              themeLibrary = themeLibrary,
              onThemeSelected = onThemeSelected,
              currentLocale = currentLocale,
              onLocaleChanged = onLocaleChanged,
              backgroundSettings = backgroundSettings,
              onBackgroundSettingsChanged = { change -> backgroundManager.update(change) },
              customization              = customization,
              onCustomizationChanged     = onCustomizationChanged,
          )

          NotificationStack()

          if (pendingLogout) {
              val s = LocalStrings.current
              DestructiveConfirmDialog(
                  title        = s.logoutConfirmTitle,
                  body         = s.logoutConfirmBody,
                  confirmLabel = s.navLogout,
                  onConfirm    = doLogout,
                  onDismiss    = { pendingLogout = false },
              )
          }
          // Automation bypass for the now two-step logout (request -> confirm).
          PuppetClick("logout.confirm") { doLogout() }
      }
    }
}

/** Getting a launch ready: the two states that change with every chunk and every file. */
private fun LaunchState.isPreparing(): Boolean = this is LaunchState.Prepare || this is LaunchState.Downloading

/** The most the image cache keeps on disk before it evicts the least recently used. */
private const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024

/**
 * How long quitting waits for a game it was asked to stop. The game gets its own
 * termination grace and then a forced kill; this only has to outlast both.
 */
private val QUIT_STOP_WAIT = 15.seconds
