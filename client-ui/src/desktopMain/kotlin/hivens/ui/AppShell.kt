package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import hivens.config.Branding
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.RefreshableAuthProvider
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.HomeView
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.core.data.UiStyle
import hivens.core.data.resolveInitialThemeMode
import hivens.launcher.AutoSyncService
import hivens.launcher.ServerListCacheStore
import hivens.launcher.bootstrap.AutoLoginCoordinator
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.ui.diag.UiRecoverySignal
import hivens.launcher.CredentialsManager
import hivens.core.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.chrome.computeSafeWindowMinSize
import hivens.launcher.ProfileManager
import hivens.ui.background.BackdropState
import hivens.ui.background.BackgroundManager
import hivens.ui.background.CustomBackground
import hivens.ui.background.LocalBackdrop
import hivens.ui.background.FrostBackdrop
import hivens.ui.surface.LocalBackdropPainter
import hivens.ui.chrome.IS_TILING_WM
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.WindowResizeHandles
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.UpdateManager
import hivens.ui.customization.CustomizationManager
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.LocalCustomization
import hivens.ui.easter.AprilFools
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.editor.EditModeController
import hivens.ui.editor.WidgetGraphReconciler
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.puppet.PuppetClick
import hivens.ui.notifications.Kind
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.screens.ConsoleWindow
import hivens.ui.screens.MigrationScreen
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.SystemTheme
import hivens.ui.theme.ThemeRevealHost
import hivens.ui.theme.rememberThemeReveal
import hivens.ui.theme.ThemeManager
import hivens.ui.system.SystemNotifier
import hivens.ui.tray.TrayManager
import hivens.ui.utils.ConsoleSettingsManager
import hivens.ui.utils.GameConsoleService
import hivens.launcher.LayoutGraphRepository
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetChromeRenderer
import hivens.widget.api.WidgetChromeRenderer
import hivens.ui.customization.glassSurfaceAlpha
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

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
    data class ServerSettings(val server: ServerProfile) : Screen()
    data class ServerDetails (val server: ServerProfile) : Screen()

    /**
     * Library card click target. Carries the PackInstance UUID; the
     * detail screen resolves it via [hivens.core.api.interfaces.IPackRepository]
     * so the Screen sealed class stays free of domain types and the
     * back-stack item stays small (a UUID string, not a PackInstance
     * graph).
     */
    data class PackDetail    (val instanceId: String) : Screen()

    /**
     * Catalogue-side detail target, source-neutral: carries the [origin] + that
     * source's local pack id. The one [hivens.ui.screens.browse.CataloguePackDetailScreen]
     * resolves both through [hivens.launcher.catalogue.PackCatalogueRegistry] +
     * [hivens.launcher.PackInstallCoordinator]. Distinct from [PackDetail], which
     * resolves an already-installed [hivens.core.data.PackInstance].
     */
    data class CataloguePackDetail(val origin: PackOrigin, val packId: String) : Screen()
}

// ─── App Shell ───────────────────────────────────────────────────────────────

/**
 * Compose-side root. Holds the Window + tray bootstrap + raise-tick
 * .show watcher + migration / AppRoot branch. Receives the
 * pre-Compose [LauncherBootstrap.Result] from [Main.main]; everything
 * below this is pure Compose.
 *
 * Extension on [ApplicationScope] so `exitApplication()` works inside
 * the window close handler without re-wiring the receiver.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ApplicationScope.AppShell(boot: LauncherBootstrap.Result) {
    // Tray teardown is composition-scoped: the tray is re-init'd per
    // composition (see the tray LaunchedEffect below), so disposing it here
    // gives a clean shutdown -> init cycle across a shell restart. Process-
    // lifetime teardown (puppet server, Koin) is deliberately NOT here: it
    // also fires when the composition is disposed on a crash, which would stop
    // Koin out from under the recovery restart loop. It lives in a JVM
    // shutdown hook in Main instead.
    DisposableEffect(Unit) {
        onDispose {
            TrayManager.shutdown()
            SystemNotifier.shutdown()
        }
    }

    // On a tiling WM the compositor owns geometry/fullscreen; forcing
    // MAXIMIZED_BOTH there fights it (Compose re-asserts maximize over the WM's
    // fullscreen, so super+F / proper fullscreen misbehaves -- worse once
    // undecorated drops the WM title bar that used to mediate it). Let the WM
    // place it. Floating elsewhere is unchanged: open maximized on Win/floating-DE.
    val windowState      = rememberWindowState(
        placement = if (IS_TILING_WM) WindowPlacement.Floating else WindowPlacement.Maximized,
    )
    val settingsService: ISettingsService      = koinInject()
    val serverListService: IServerListService  = koinInject()
    val serverListCache: ServerListCacheStore  = koinInject()
    val controller: LauncherController         = koinInject()
    val launchDriver: LaunchDriver             = koinInject()
    val credentialsManager: CredentialsManager = koinInject()
    val authService: AuthProvider              = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val gameConsole: GameConsoleService        = koinInject()
    val layoutGraphRepo: LayoutGraphRepository = koinInject()
    val widgetRegistry: WidgetRegistry         = koinInject()
    val widgetServiceRegistry: WidgetServiceRegistry = koinInject()
    val widgetDataRegistry: WidgetDataRegistry = koinInject()
    val widgetCommandRegistry: WidgetCommandRegistry = koinInject()
    val widgetStateStore: WidgetStateStore = koinInject()
    val editModeController: EditModeController  = koinInject()
    // Shared process-lifetime scope (createdAtStart in appModule; canceled
    // by AppCoroutineScopeHook on JVM shutdown). Same instance backs
    // LauncherController.appScope and any other fire-and-forget work.
    val applicationScope: CoroutineScope        = koinInject()

    val settings = remember { settingsService.getSettings() }

    // Window starts visible. Tray is the dock-style fallback for
    // close-while-game-running, not a launcher hide-by-default
    // mode -- a start-in-tray toggle was tried and dropped; it
    // confused users (launcher invisible after first run) without
    // a clear use case.
    var isWindowVisible by remember { mutableStateOf(true) }

    var isDarkTheme   by remember { mutableStateOf(settings.isDarkTheme) }
    // Material You palette: the wallpaper seed (computed in AppRoot from the backdrop
    // bitmap) lifts up to here so NxTheme -- which wraps AppRoot -- can derive
    // the palette from it. Default-on; the seed is null until a bitmap is decoded.
    val paletteFromWallpaper = settings.paletteFromWallpaper
    var wallpaperSeed by remember { mutableStateOf<Int?>(null) }
    // Which source drives dark/light: the manual toggle, the OS scheme, or the
    // wallpaper's brightness. Both automatic sources write through isDarkTheme (and
    // persist it), so everything downstream keeps reading one boolean.
    var themeMode by remember { mutableStateOf(resolveInitialThemeMode(settings)) }
    // Whether the OS scheme is readable at all (no portal backend on Linux -> no).
    // Probed once; drives the System chip's enabled state in the theme island.
    var systemThemeAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { systemThemeAvailable = SystemTheme.probe() != null }
    var wallpaperLuminance by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(themeMode, wallpaperLuminance) {
        val luma = wallpaperLuminance
        if (themeMode == ThemeMode.Wallpaper && luma != null) {
            val wantDark = luma < 0.5f
            if (wantDark != isDarkTheme) {
                isDarkTheme = wantDark
                settingsService.saveSettings(settingsService.getSettings().copy(isDarkTheme = isDarkTheme))
            }
        }
    }
    // System mode: follow the OS scheme while the mode is active -- the cold flow
    // (portal signal on Linux, polling fallback) runs only while collected, so the
    // other modes cost nothing. Persisting each flip keeps the next cold start on
    // the last observed scheme (no startup flash).
    LaunchedEffect(themeMode) {
        if (themeMode != ThemeMode.System) return@LaunchedEffect
        SystemTheme.observe().collect { dark ->
            if (dark != null && dark != isDarkTheme) {
                isDarkTheme = dark
                settingsService.saveSettings(settingsService.getSettings().copy(isDarkTheme = dark))
            }
        }
    }
    var currentLocale by remember {
        mutableStateOf(AppLocale.fromTag(settings.locale))
    }
    var homeView      by remember { mutableStateOf(settings.homeView) }
    var uiStyle       by remember { mutableStateOf(settings.uiStyle) }

    val basePresetStyle = when (uiStyle) {
        UiStyle.Celestia -> CelestiaStyle
        UiStyle.Brut     -> BrutStyle
    }
    // Preset-only spec at this level -- customization (and the
    // editor-4 style overrides) live inside AppRoot. AprilFools
    // tracks the preset value; the overridden value flows through
    // LocalStyle further down for composables that need the
    // user-tweaked tokens.
    val styleSpec = basePresetStyle

    // Push style coupling into AprilFools so the chaos engine (a plain
    // singleton, not a Composable) and chaos components pick up the
    // active style without having to thread a CompositionLocal through
    // them. Triggered on every uiStyle change.
    LaunchedEffect(styleSpec) {
        AprilFools.styleAnimationMultiplier = styleSpec.animationMultiplier
        AprilFools.useFlatSurface           = styleSpec.cardSurface == hivens.ui.theme.CardSurface.Flat
    }

    // One-time registry-aware reconcile of the loaded layout graph. The
    // launcher seeds missing bundled-default surfaces/slots but has no
    // WidgetRegistry, so descriptor-declared container child slots are seeded
    // here -- otherwise a container persisted before child-slot seeding (or one
    // whose descriptor gained a slot) silently refuses nested drops. Idempotent:
    // a healthy graph reconciles to itself and writes nothing.
    LaunchedEffect(Unit) {
        val before = layoutGraphRepo.value()
        val defaultKinds = DefaultLayout.load().walkInstances().map { it.kind }.toSet()
        val result = WidgetGraphReconciler.reconcile(
            graph        = before,
            registry     = widgetRegistry,
            defaultKinds = defaultKinds,
            // Prune removed kinds only when a schema bump actually happened --
            // a deliberate app update is the safe moment to reap orphans.
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

    val launchState by controller.state.collectAsState()

    // Drains the controller's event channel into the console pane with
    // localized text. Lives at this level (not Dashboard) so events fire
    // regardless of which screen the user is currently viewing; the
    // collector is the seam that lets LauncherController stay free of
    // `client-ui` types (i18n, console). See `LaunchLogCollector` for the
    // event-to-string mapping.
    hivens.ui.logic.LaunchLogCollector(events = controller.events, gameConsole = gameConsole)


    // Bumped each time the .show signal fires; the Window content uses it
    // to invoke window.toFront() / requestFocus() so a duplicate-launch
    // attempt actually raises the existing instance, not just makes it
    // visible-but-buried-under-other-windows.
    var raiseTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val showFile = boot.paths.dataDir.resolve(".show").toFile()
        while (true) {
            delay(500.milliseconds)
            if (showFile.exists()) {
                showFile.delete()
                // Un-minimize: setting visible=true alone leaves a taskbar-minimized window minimized.
                if (windowState.isMinimized) windowState.isMinimized = false
                isWindowVisible = true
                raiseTick++
            }
        }
    }

    LaunchedEffect(launchState) {
        val serverName = profileManager.lastServerId
        when (launchState) {
            is LaunchState.GameRunning -> TrayManager.setGameStatus(true, serverName)
            is LaunchState.Error -> {
                TrayManager.setGameStatus(false)
                if (!isWindowVisible) {
                    SwingUtilities.invokeLater { isWindowVisible = true }
                }
            }
            else -> TrayManager.setGameStatus(false)
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

        // Came back from a crash restart: surface a one-shot notice so the reload
        // -- which resets the current screen -- is not silent. consumeRecovered()
        // is one-shot, so a normal start stays quiet.
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

        val dataDirectory: java.nio.file.Path = koinInject()
        val autoSyncService: AutoSyncService = koinInject()
        val themeManager  = remember { ThemeManager(dataDirectory) }
        var customTheme   by remember { mutableStateOf(themeManager.loadTheme()) }

        // Customization extension: persisted overrides for accent /
        // density / glass intensity / full color overrides. Provided
        // via [LocalCustomization] so NxTheme + GlassCard can
        // read without prop-drilling.
        val customizationJson    = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
        val customizationManager = remember { CustomizationManager(dataDirectory, customizationJson) }
        var customization        by remember { mutableStateOf(customizationManager.load()) }

        // Per-domain console preferences -- the same JSON-file-per-manager
        // shape as customization / background. Loaded eagerly so the
        // first render uses persisted font / wrap / gutter choices.
        val consoleSettingsManager = remember { ConsoleSettingsManager(dataDirectory, customizationJson) }
        var consoleSettings        by remember { mutableStateOf(consoleSettingsManager.load()) }

        // Window chrome icon -- KDE overview / Hyprland switcher / macOS
        // dock want the detailed hi-res asset so they can be downscale
        // cleanly to whatever the compositor demands. The tray builds
        // its 64-px glyph from `drawable/favicon.png` separately via
        // `Res.readBytes(...)` so it doesn't need a Painter here.
        val windowIcon = painterResource(Res.drawable.icon)

        // Localized tray labels, derived from the active locale's strings.
        // Strings is a data class, so its structural equality lets the
        // locale-reactive effect below re-fire only when a label actually
        // changes -- not on every unrelated recomposition.
        val trayLabels = TrayManager.Strings(
            statusIdle    = s.trayStatusIdle,
            statusRunning = s.trayStatusRunning,
            show          = s.trayShow,
            console       = s.trayConsole,
            servers       = s.trayServers,
            noServers     = s.trayNoServers,
            exit          = s.trayExit,
        )

        // ── Tray bring-up + server population (run once) ──────────────
        // The IO ordering here is load-bearing: seed the tray from the disk
        // cache BEFORE init() so the first published menu already carries
        // servers, then init, then overwrite with the live dashboard fetch.
        // Callback wiring and locale-reactive labels are split into their own
        // focused effects below.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                // Stale-while-revalidate: seed [TrayManager] from the disk
                // cache BEFORE init() so libtray's first published DBusMenu
                // layout already carries real servers. Without this seed,
                // a user right-clicking the tray icon during the 0.5-3s
                // window before [fetchDashboardData] returns sees the
                // "(No servers)" placeholder and concludes the tray is
                // broken. The live fetch below overwrites the seed.
                val cachedServers = serverListCache.load()
                if (cachedServers.isNotEmpty()) {
                    TrayManager.updateServers(cachedServers)
                }

                try {
                    val iconBytes = Res.readBytes("drawable/favicon.png")
                    TrayManager.init(
                        iconStream = iconBytes.inputStream(),
                        strings    = trayLabels,
                        appName    = Branding.TITLE
                    )
                    SystemNotifier.init(appName = Branding.TITLE, appId = NEXIRA_APP_ID, iconBytes = iconBytes)
                } catch (_: Exception) {
                    runCatching {
                        val iconBytes = Res.readBytes("drawable/icon.png")
                        TrayManager.init(
                            iconStream = iconBytes.inputStream(),
                            strings    = trayLabels,
                            appName    = Branding.TITLE
                        )
                        SystemNotifier.init(appName = Branding.TITLE, appId = NEXIRA_APP_ID, iconBytes = iconBytes)
                    }
                }
            }

            // Tray failed to init -- restore the window so the user isn't
            // stuck with no reachable UI. The scenario is: user clicked
            // close during INITIALIZING window (the close handler uses
            // canBeReady, not isSupported, to avoid killing the launcher
            // mid-init). Without this restore the process keeps running
            // with no UI and the user has to kill it.
            if (!TrayManager.isSupported && !isWindowVisible) {
                isWindowVisible = true
            }

            // ── Populate server list ───────────────────────────────────
            // [SmartyCraftServerListService.fetchDashboardData] swallows
            // network failures and returns `DashboardData(empty, empty)`
            // rather than throwing, so an outage looks like a successful
            // empty fetch from this call site. Without an explicit
            // fetch-failed signal we cannot fully distinguish "outage"
            // from "admin truly cleared the roster"; use the disk cache
            // [serverListCache] as a heuristic: a previously-cached
            // non-empty roster going to empty implies outage and we
            // keep the seed; an already-empty cache going to empty is
            // accepted as the new truth.
            //
            // Tradeoff: false-positive (admin actually cleared the
            // roster while cache exists) keeps a stale entry for ONE
            // session until next launch's fresh load(); the
            // false-negative (transient outage wipes a real seed)
            // hurts more, so the heuristic leans toward seed
            // preservation. Reviewer flagged the previous "always
            // preserve on empty" version as conflating both cases.
            // Re-read the on-disk cache here -- the seed read inside
            // the tray-init withContext block is local to that lambda,
            // and re-reading is a cheap ~2 KB JSON load. The value is
            // the heuristic we use to distinguish outage from a
            // legitimately-empty roster below.
            val seedFromCache = withContext(Dispatchers.IO) { serverListCache.load() }
            val dashboardServers = try {
                val data = withContext(Dispatchers.IO) {
                    serverListService.fetchDashboardData().get()
                }
                when {
                    data.servers.isNotEmpty() -> {
                        TrayManager.updateServers(data.servers)
                        data.servers
                    }
                    seedFromCache.isEmpty() -> {
                        // No seed to preserve; accept the empty roster
                        // and let the tray render "(No servers)".
                        TrayManager.updateServers(emptyList())
                        emptyList()
                    }
                    else -> {
                        // Probable outage: keep the seeded tray entries
                        // from the disk cache and return them so the
                        // dashboard surface stays populated.
                        seedFromCache
                    }
                }
            } catch (e: CancellationException) {
                // Composition leave / locale switch / exit mid-fetch
                // must propagate cooperatively; converting to "outage"
                // would defeat structured concurrency.
                throw e
            } catch (_: Exception) {
                /* tray keeps the seeded cache from the IO init block */
                seedFromCache
            }

            // ── Auto-sync (experimental, opt-in) ──────────────────────
            // Fire-and-forget background sync of every installed pack.
            // Gated by experimentalFeaturesEnabled master + autoSyncAllPacks
            // child to match the rest of the experimental opt-ins. Runs on
            // applicationScope so it survives composition resets but does
            // get cancelled on JVM exit -- the alternative (GlobalScope)
            // leaks network/file handles past window close until the
            // process actually exits. The service itself is a singleton
            // and idempotent (no-ops on subsequent calls while already
            // running).
            if (settings.experimentalFeaturesEnabled
                && settings.autoSyncAllPacks
                && dashboardServers.isNotEmpty()
            ) {
                applicationScope.launch {
                    autoSyncService.syncAll(dashboardServers)
                }
            }
        }

        // ── Tray / notifier callbacks (run once) ──────────────────────
        // Every captured reference is a stable singleton or remembered state,
        // so a Unit key is correct -- the assignments need to happen exactly
        // once, not on every recomposition.
        LaunchedEffect(Unit) {
            TrayManager.onShowWindow = {
                SwingUtilities.invokeLater { isWindowVisible = true }
            }

            // libnotify fires on its own thread, so hop to the AWT thread
            // before touching window state.
            SystemNotifier.onShowWindow = {
                SwingUtilities.invokeLater { isWindowVisible = true }
            }

            TrayManager.onExit = {
                SwingUtilities.invokeLater {
                    // Real impl pops the chaos close-dialog (during April Fools
                    // window); NoOp invokes onActualClose synchronously. The
                    // visibility flip is unconditional during chaos so the
                    // dialog isn't hidden behind a minimized window.
                    if (af.isActive()) isWindowVisible = true
                    af.requestCloseDialog { exitApplication() }
                }
            }

            TrayManager.onShowConsole = {
                SwingUtilities.invokeLater { gameConsole.show() }
            }

            TrayManager.onLaunchServer = { server ->
                applicationScope.launch {
                    val credentials = credentialsManager.load()
                    if (credentials?.cachedPassword != null) {
                        try {
                            val session = try {
                                authService.login(
                                    credentials.playerName,
                                    credentials.cachedPassword!!,
                                    server.assetDir,
                                )
                            } catch (_: TwoFactorRequiredException) {
                                // Tray-launched 2FA accounts: same trust-the-cache
                                // policy as the auto-login path. controller.launch
                                // augments the session with a cached manifest if
                                // needed (and reports cleanly when the cache is
                                // empty, which is its job).
                                credentials.copy(serverId = server.assetDir)
                            }
                            launchDriver.observe(LaunchTarget.Server(server))
                            controller.launch(session, server)
                        } catch (e: Exception) {
                            LoggerFactory.getLogger("Main").warn(
                                "Tray-launched login failed for ${server.assetDir}", e
                            )
                            SwingUtilities.invokeLater { isWindowVisible = true }
                        }
                    } else {
                        SwingUtilities.invokeLater { isWindowVisible = true }
                    }
                }
            }
        }

        // ── Locale-reactive tray labels ───────────────────────────────
        // init() captures the first locale's labels and no-ops afterwards;
        // this republishes them when the user switches language at runtime so
        // the tray menu + tooltip don't stay stuck in the startup locale.
        LaunchedEffect(trayLabels) {
            TrayManager.updateStrings(trayLabels)
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
                settingsService.saveSettings(settingsService.getSettings().copy(trayHintShown = true))
            }
        }

        // Console window moved inside the CompositionLocalProvider /
        // NxTheme block below so it inherits the active theme +
        // customization (accent override, role overrides). The window
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
                if (TrayManager.canBeReady) {
                    // canBeReady (not isSupported) so we don't kill the launcher
                    // mid-init while the tray library is still settling D-Bus /
                    // SNI handshake. If it ultimately fails, the user can quit via
                    // tray (when it appears) or kill the process -- strictly
                    // better than exiting on a close request the user clearly
                    // meant as "minimize".
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            }
        }
        Window(
            onCloseRequest = onCloseChrome,
            state     = windowState,
            visible   = isWindowVisible,
            title     = Branding.TITLE,
            // Replace the OS title bar with our own -- but ONLY where the OS draws
            // one. A tiling WM draws no title bar for tiled windows (nothing to
            // hide), and an undecorated AWT window there ignores the WM's external
            // fullscreen state (_NET_WM_STATE_FULLSCREEN), so super+F breaks. So go
            // undecorated only off-tiling; on tiling stay decorated (WM shows no
            // chrome anyway) and the top bar still renders -- it is layout, not
            // window decoration. useCustomChrome=false forces OS decorations always.
            // transparent stays false -- opt-in later for Float corners.
            undecorated = settings.useCustomChrome && !IS_TILING_WM,
            resizable = true,
            icon      = windowIcon,
            onPreviewKeyEvent = { ev ->
                // Window-scoped chords (preview = before focus dispatch) so they
                // fire no matter which composable holds focus -- the side rails
                // own focus, so a host Box-level handler misses them. Consume
                // both edges so they never reach a focused control, and act on
                // release only so holding does not repeat on auto-repeat KeyDowns.
                when {
                    // Ctrl+E toggles widget edit mode. EditorSurfaceHost observes
                    // the controller signal and gates on its surface being editable.
                    ev.isCtrlPressed && ev.key == Key.E -> {
                        if (ev.type == KeyEventType.KeyUp) editModeController.requestEditToggle()
                        true
                    }
                    // Ctrl+N collapses / expands the right rail. ShellRightRegion
                    // observes the signal and flips its collapsed prop.
                    ev.isCtrlPressed && ev.key == Key.N -> {
                        if (ev.type == KeyEventType.KeyUp) editModeController.requestRightRailToggle()
                        true
                    }
                    else -> false
                }
            },
        ) {
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

            val baseDensity   = androidx.compose.ui.platform.LocalDensity.current
            val scaledDensity = remember(baseDensity, customization.densityScale) {
                androidx.compose.ui.unit.Density(
                    baseDensity.density * customization.densityScale.coerceIn(0.5f, 2f),
                    baseDensity.fontScale,
                )
            }
            val layoutGraph by layoutGraphRepo.observe().collectAsState()
            // Production renderer for per-widget backing (WidgetChrome): glass
            // card (follows the active style via glassSurfaceAlpha), rounded
            // corners, inner padding. Invoked by the kernel only when a widget
            // carries chrome, so default-styled widgets pay nothing.
            // Remembered so its identity stays stable across AppShell
            // recomposes. It is provided through the *static*
            // LocalWidgetChromeRenderer, so a fresh identity each recompose
            // would invalidate the whole content subtree, not just chrome
            // consumers. The lambda captures nothing mutable -- glassSurfaceAlpha
            // reads its CompositionLocals at invoke time, inside composition.
            val chromeRenderer: WidgetChromeRenderer = remember {
                { chrome, content ->
                    val glass = glassSurfaceAlpha(chrome.glassAlphaPct / 100f)
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            // Padding is an OUTER inset, applied before the backing, so
                            // the rounded glass hugs the widget's own view -- the corner
                            // radius describes the widget, not the padded footprint.
                            // Padding the right panel insets it from the edges without
                            // the rounding detaching onto the padded box.
                            .padding(
                                PaddingValues(
                                    start  = chrome.effectiveStart.dp,
                                    top    = chrome.effectiveTop.dp,
                                    end    = chrome.effectiveEnd.dp,
                                    bottom = chrome.effectiveBottom.dp,
                                ),
                            )
                            .then(
                                if (chrome.cornerRadiusDp > 0)
                                    Modifier.clip(RoundedCornerShape(chrome.cornerRadiusDp.dp))
                                else Modifier,
                            )
                            .background(glass),
                    ) { content() }
                }
            }
            CompositionLocalProvider(
                LocalCustomization                       provides customization,
                androidx.compose.ui.platform.LocalDensity provides scaledDensity,
                LocalLayoutGraph                         provides layoutGraph,
                LocalWidgetRegistry                      provides widgetRegistry,
                LocalWidgetServiceRegistry               provides widgetServiceRegistry,
                LocalWidgetDataRegistry                  provides widgetDataRegistry,
                LocalWidgetCommandRegistry               provides widgetCommandRegistry,
                LocalWidgetStateHost                     provides widgetStateStore,
                LocalWidgetChromeRenderer                provides chromeRenderer,
                LocalWindowState                         provides windowState,
                LocalComposeWindow                       provides window,
                LocalChromeClose                         provides onCloseChrome,
                LocalUseCustomChrome                     provides settings.useCustomChrome,
            ) {
            val effectiveStyle = styleSpec

            // Console runs as its own OS window but is composed from here so
            // it inherits LocalCustomization + LocalNxColors via the
            // Compose composition tree. The internal NxTheme wrap is
            // what actually projects the palette into the window's surface;
            // this site only ensures the composition locals are in scope.
            if (gameConsole.shouldShowConsole) {
                ConsoleWindow(
                    isDarkTheme    = isDarkTheme,
                    onClose        = { gameConsole.hide() },
                    customTheme    = customTheme,
                    style          = effectiveStyle,
                    settings       = consoleSettings,
                    onSettingsChange = { updated ->
                        consoleSettings = updated
                        consoleSettingsManager.save(updated)
                    },
                )
            }

            Box(Modifier.fillMaxSize()) {
            val themeReveal = rememberThemeReveal()
            NxTheme(
                useDarkTheme = isDarkTheme,
                customTheme  = customTheme,
                style        = effectiveStyle,
                paletteSeed  = wallpaperSeed,
                paletteFromWallpaper = paletteFromWallpaper,
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
                        onQuit = { exitApplication() },
                    )
                } else {
                    AppRoot(
                        onCloseApp = {
                            val gameRunning = launchState is LaunchState.GameRunning
                            if (gameRunning && TrayManager.canBeReady) {
                                // Same canBeReady reasoning as the Window
                                // onCloseRequest: don't pull the rug from
                                // under a running game just because tray
                                // init is still mid-flight.
                                isWindowVisible = false
                            } else {
                                exitApplication()
                            }
                        },
                        onWallpaperSeed = { wallpaperSeed = it },
                        onWallpaperLuminance = { wallpaperLuminance = it },
                        onRealExit   = { exitApplication() },
                        onHideToTray = if (TrayManager.canBeReady) {{ isWindowVisible = false }}
                        else null,
                        isDarkTheme          = isDarkTheme,
                        onToggleDarkTheme    = {
                            // An explicit flip always wins: leaving an automatic mode
                            // drops back to Manual in the same save.
                            isDarkTheme = !isDarkTheme
                            themeMode = ThemeMode.Manual
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(
                                isDarkTheme = isDarkTheme,
                                themeMode = ThemeMode.Manual,
                                themeFromWallpaper = false,
                            ))
                        },
                        themeMode = themeMode,
                        onThemeModeChanged = { mode ->
                            themeMode = mode
                            // themeFromWallpaper mirrors the mode so a downgrade to a
                            // pre-mode build keeps the wallpaper opt-in coherent.
                            settingsService.saveSettings(settingsService.getSettings().copy(
                                themeMode = mode,
                                themeFromWallpaper = mode == ThemeMode.Wallpaper,
                            ))
                        },
                        systemThemeAvailable = systemThemeAvailable,
                        customTheme          = customTheme,
                        onCustomThemeChanged = { newTheme ->
                            customTheme = newTheme
                            themeManager.saveTheme(newTheme)
                        },
                        currentLocale   = currentLocale,
                        onLocaleChanged = { newLocale ->
                            currentLocale = newLocale
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(locale = newLocale.tag))
                        },
                        homeView           = homeView,
                        onHomeViewChanged = { newView ->
                            homeView = newView
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(homeView = newView))
                        },
                        uiStyle           = uiStyle,
                        onUiStyleChanged  = { newStyle ->
                            uiStyle = newStyle
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(uiStyle = newStyle))
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
            // Synthetic resize grips -- undecorated drops the native border. Only
            // with custom chrome (else the OS frame resizes); self-gates to
            // Floating + non-tiling and is transparent, so it's otherwise harmless.
            if (settings.useCustomChrome) {
                WindowResizeHandles(
                    state   = windowState,
                    minSize = DpSize(MIN_WINDOW_WIDTH_DP.dp, MIN_WINDOW_HEIGHT_DP.dp),
                )
            }
            } // end Box(window resize overlay)
            } // end CompositionLocalProvider(LocalCustomization + LocalDensity)
        }
    }
    } // end CompositionLocalProvider(LocalAprilFools)
}

// ─── App Root ─────────────────────────────────────────────────────────────────

@Composable
fun AppRoot(
    onCloseApp: () -> Unit,
    onWallpaperSeed: (Int?) -> Unit,
    onWallpaperLuminance: (Float?) -> Unit,
    isDarkTheme: Boolean,
    onRealExit: () -> Unit,
    onHideToTray: (() -> Unit)?,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    customTheme: CustomTheme,
    onCustomThemeChanged: (CustomTheme) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    homeView: HomeView,
    onHomeViewChanged: (HomeView) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
) {
    val credentialsManager: CredentialsManager = koinInject()
    val authService: AuthProvider              = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val settingsService: ISettingsService      = koinInject()
    val dataDirectory: java.nio.file.Path      = koinInject()
    val json: Json                             = koinInject()
    val insecureAuthService: AuthProvider      = koinInject(named("insecure"))
    val protocolConfig: ServerProtocolConfig   = koinInject()
    val authRegistry: AuthProviderRegistry     = koinInject()
    // Present only when a Microsoft client id is configured -- the registry holds
    // the refreshable provider exactly then, so auto-login is gated by its presence.
    val msaProvider: RefreshableAuthProvider?  =
        authRegistry.all.filterIsInstance<RefreshableAuthProvider>().firstOrNull()
    // Smartycraft-routed Call.Factory for Coil's image fetcher. The
    // bypass / forceProxy / direct routing rule lives in Modules.kt
    // alongside the same rule for the Ktor HttpClientProvider; both
    // must agree or news / skin images would diverge from auth and
    // protocol traffic on the same host.
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
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { routingCallFactory }))
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
    val doLogout = { credentialsManager.clear(); appState = AppState.Unauthenticated }

    // Mouse side buttons (back/forward) -> history navigation. Compose's pointer
    // layer only surfaces primary/secondary/tertiary on this platform, so listen at
    // the AWT level where the thumb buttons still arrive. The AWT event thread is
    // the Compose UI thread in Compose Desktop, so mutating the NavBackStack here is
    // on the right thread.
    DisposableEffect(Unit) {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = AWTEventListener { ev ->
            if (ev is MouseEvent && ev.id == MouseEvent.MOUSE_PRESSED) {
                // AWT numbers the thumb buttons inconsistently across mice / X11
                // setups (4/5 on some, 6/7 on others); lower of each pair = Back.
                when (ev.button) {
                    4, 6 -> backStack.back()
                    5, 7 -> backStack.forward()
                }
            }
        }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }

    // ── Background settings ───────────────────────────────────────────────
    val backgroundManager = remember { BackgroundManager(dataDirectory, json) }
    var backgroundSettings by remember { mutableStateOf(backgroundManager.load()) }

    // ── Auto-login with offline mode support ──────────────────────────────
    // Business logic lives in AutoLoginCoordinator; the Composable just
    // calls into it and maps the result into the local AppState machine.
    LaunchedEffect(Unit) {
        val settings = withContext(Dispatchers.IO) { settingsService.getSettings() }
        val saved = withContext(Dispatchers.IO) {
            credentialsManager.primarySession(settings.preferredFaceProvider)
        }
        val session = withContext(Dispatchers.IO) {
            AutoLoginCoordinator.resolveSession(
                settings            = settings,
                saved               = saved,
                lastServerId        = profileManager.lastServerId,
                authService         = authService,
                insecureAuthService = insecureAuthService,
                protocolConfig      = protocolConfig,
                msaProvider         = msaProvider,
            )
        }
        // A silent MSA refresh rotates the refresh token; persist it so the next
        // start uses the fresh one instead of re-spending the stored token.
        if (session?.refreshToken != null && session.refreshToken != saved?.refreshToken) {
            withContext(Dispatchers.IO) {
                credentialsManager.saveAccount(session, PackAuthRequirement.Microsoft.PROVIDER_KEY)
            }
        }
        appState = if (session != null) AppState.Authenticated(session) else AppState.Unauthenticated
    }

    // ── Render: background behind layout ──────────────────────────────────
    val mousePos    = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val mousePxPos  = remember { mutableStateOf(Offset.Zero) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    // Wallpaper recipe published by CustomBackground so frosted surfaces can
    // redraw a blurred slice of it. EMPTY until an image is set.
    var backdrop   by remember { mutableStateOf(BackdropState.EMPTY) }

    // Material You: forward the wallpaper palette seed (computed in CustomBackground
    // from the static bitmap or the first video frame) up to NxTheme.
    LaunchedEffect(backdrop.seedArgb) { onWallpaperSeed(backdrop.seedArgb) }
    LaunchedEffect(backdrop.avgLuminance) { onWallpaperLuminance(backdrop.avgLuminance) }

    Box(
        Modifier
            .fillMaxSize()
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
      CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalBackdropPainter provides { blur, mod -> FrostBackdrop(extraBlurDp = blur, modifier = mod) },
      ) {
        CustomBackground(
            settings         = backgroundSettings,
            mousePosProvider = { mousePos.value },
            onBackdrop       = { backdrop = it },
        )

        af.WrapContent(
            pixelCursorState = mousePxPos,
            windowSize       = windowSize,
            onRealClose      = onRealExit,
            onHideTray       = onHideToTray,
        ) {
            AppLayout(
                appState = appState,
                onCloseApp = onCloseApp,
                currentScreen = backStack.current,
                onScreenChange = backStack::navigate,
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
                customTheme = customTheme,
                onCustomThemeChanged = onCustomThemeChanged,
                currentLocale = currentLocale,
                onLocaleChanged = onLocaleChanged,
                homeView = homeView,
                onHomeViewChanged = onHomeViewChanged,
                uiStyle = uiStyle,
                onUiStyleChanged = onUiStyleChanged,
                backgroundSettings = backgroundSettings,
                onBackgroundSettingsChanged = { newSettings ->
                    backgroundSettings = newSettings
                    backgroundManager.save(newSettings)
                },
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
      } // end CompositionLocalProvider(LocalBackdrop)
    }
}
