package hivens.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.model.ServerProfile
import hivens.core.data.HomeView
import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.core.data.UiStyle
import hivens.core.security.SslBypassStore
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.hasUsableImage
import hivens.ui.customization.CustomizationSettings
import hivens.ui.easter.LocalAprilFools
import hivens.ui.editor.EditorSurfaceHost
import hivens.ui.i18n.AppLocale
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxSwap
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.screens.browse.BrowseScreen
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.ui.screens.detail.versions.PackVersionsScreen
import hivens.ui.screens.library.LibraryScreen
import hivens.ui.screens.settings.SettingsScreen
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.bgsettings.BgSettingsSurface
import hivens.ui.widgets.profile.ProfileSurface
import hivens.ui.widgets.wardrobe.WardrobeSurface
import hivens.ui.widgets.serverdetails.ServerDetailsSurface
import hivens.ui.widgets.shell.LeftRailContext
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalShellContext
import hivens.ui.widgets.shell.ShellContext
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject

// ─── Layout ──────────────────────────────────────────────────────────────────

@Composable
fun AppLayout(
    appState: AppState,
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    onReplaceScreen: (Screen) -> Unit = {},
    onBack: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onForward: () -> Unit,
    trail: List<Screen>,
    onPopTo: (Screen) -> Unit,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode = ThemeMode.Manual,
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    systemThemeAvailable: Boolean = false,
    paletteFromWallpaper: Boolean = true,
    onPaletteFromWallpaperChanged: (Boolean) -> Unit = {},
    customTheme: CustomTheme,
    onCustomThemeChanged: (CustomTheme) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    homeView: HomeView,
    onHomeViewChanged: (HomeView) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    onBackgroundSettingsChanged: (BackgroundSettings) -> Unit = {},
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
) {
    val protocolConfig: ServerProtocolConfig = koinInject()

    // Session can be refreshed by DashboardScreen on auth-retry
    var currentSession by remember(appState) {
        mutableStateOf((appState as? AppState.Authenticated)?.session)
    }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }

    // Go transparent only when the wallpaper can actually be drawn -- a deleted
    // image left the row transparent over a blank white window.
    val rowBackground = if (backgroundSettings.hasUsableImage()) Color.Transparent
    else NxTheme.colors.background

    val bypassHost = protocolConfig.sslBypassHost
    val bypassStore: SslBypassStore = koinInject()
    val bypassesList by bypassStore.bypasses.collectAsState()
    val sslBypass = remember(bypassesList, bypassHost) { bypassStore.isBypassed(bypassHost) }

    // The center region's screen router. Defined here (not in the layout graph)
    // because navigation is not yet a widget surface; the center region widget
    // invokes it. Reads currentSession/selectedServer live on each recompose.
    val centerBody: @Composable () -> Unit = {
        // How a screen replaces another is the swap primitive's business, not the
        // router's. A still style collapses it without this site knowing.
        NxSwap(
            target = currentScreen,
            label  = "screen",
        ) { screen ->
                when (screen) {
                    Screen.Home -> {
                        val session = currentSession
                        when (homeView) {
                            // The classic dashboard IS the SmartyCraft server list, so it
                            // is genuinely gated on auth. `Loading` is the brief window
                            // between startup and resolved credentials -- spinner is
                            // appropriate; `Unauthenticated` is a stable state waiting on
                            // user input, so it gets the explicit sign-in copy + route.
                            HomeView.Classic -> when {
                                session != null -> DashboardScreen(
                                    session               = session,
                                    initialSelectedServer = selectedServer,
                                    onServerSelected      = { selectedServer = it },
                                    onSessionUpdated      = { currentSession = it },
                                    onOpenServerSettings  = { onScreenChange(Screen.ServerSettings(it.assetDir)) },
                                    onOpenDetails         = { onScreenChange(Screen.ServerDetails(it.assetDir)) }
                                )
                                appState is AppState.Loading -> ContentLoadingPlaceholder()
                                else -> ContentLoginRequiredPlaceholder(
                                    onSignIn = { onScreenChange(Screen.Profile) },
                                )
                            }
                            // The pack-centric variants run on LOCAL data (pack repo,
                            // layout graph) and render signed-out; their launch
                            // affordances degrade per-widget (offline / sign-in)
                            // instead of gating the whole page on an SC session.
                            HomeView.LibraryFirst -> LibraryScreen(
                                appState       = appState,
                                onScreenChange = onScreenChange,
                            )
                            HomeView.New -> NewHomeScreen(
                                appState         = appState,
                                onScreenChange   = onScreenChange,
                                onSessionUpdated = { currentSession = it },
                            )
                        }
                    }

                    Screen.Profile ->
                        ProfileSurface(
                            session       = currentSession,
                            authResolving = appState is AppState.Loading,
                            onLogin       = onLogin,
                            onLogout      = onLogout,
                        )

                    Screen.Wardrobe ->
                        WardrobeSurface(session = currentSession, onBack = onBack)

                    Screen.Settings ->
                        SettingsScreen(
                            isDarkTheme                  = isDarkTheme,
                            onToggleTheme                = onToggleDarkTheme,
                            onOpenThemePicker            = { onScreenChange(Screen.ThemePicker) },
                            currentLocale                = currentLocale,
                            onLocaleChanged              = onLocaleChanged,
                            homeView                     = homeView,
                            onHomeViewChanged            = onHomeViewChanged,
                            uiStyle                      = uiStyle,
                            onUiStyleChanged             = onUiStyleChanged,
                            onOpenBackgroundSettings     = { onScreenChange(Screen.BackgroundSettings) },
                            onOpenAbout                  = { onScreenChange(Screen.About) },
                        )

                    Screen.ThemePicker ->
                        ThemePickerSurface(
                            currentTheme    = customTheme,
                            onThemeSelected = { newTheme ->
                                onCustomThemeChanged(newTheme)
                                onBack()
                            },
                            onBack          = onBack,
                        )

                    Screen.About ->
                        AboutSurface(onBack = onBack)

                    Screen.BackgroundSettings ->
                        BgSettingsSurface(
                            currentSettings   = backgroundSettings,
                            onSettingsChanged = onBackgroundSettingsChanged,
                            onBack            = onBack,
                            isDarkTheme       = isDarkTheme,
                            onToggleDarkTheme = onToggleDarkTheme,
                            themeMode = themeMode,
                            onThemeModeChanged = onThemeModeChanged,
                            systemThemeAvailable = systemThemeAvailable,
                            paletteFromWallpaper = paletteFromWallpaper,
                            onPaletteFromWallpaperChanged = onPaletteFromWallpaperChanged,
                            uiStyle           = uiStyle,
                            onUiStyleChanged  = onUiStyleChanged,
                            onOpenThemePicker = { onScreenChange(Screen.ThemePicker) },
                        )

                    is Screen.ServerSettings ->
                        WithServer(screen.serverId, onBack) { server ->
                            ServerSettingsScreen(
                                server = server,
                                onBack = onBack
                            )
                        }

                    is Screen.ServerDetails ->
                        WithServer(screen.serverId, onBack) { server ->
                            ServerDetailsSurface(
                                server = server,
                                onBack = onBack,
                            )
                        }

                    Screen.Library -> LibraryScreen(
                        appState       = appState,
                        onScreenChange = onScreenChange,
                    )

                    Screen.Browse  -> BrowseScreen(
                        onOpenPack = { pack ->
                            onScreenChange(Screen.CataloguePackDetail(pack.origin, pack.id))
                        },
                    )

                    is Screen.CataloguePackDetail ->
                        hivens.ui.screens.browse.CataloguePackDetailScreen(
                            origin      = screen.origin,
                            packId      = screen.packId,
                            onBack      = onBack,
                            onInstalled = { instanceId -> onScreenChange(Screen.PackDetail(instanceId)) },
                        )

                    is Screen.PackDetail ->
                        PackDetailScreen(
                            instanceId          = screen.instanceId,
                            appState            = appState,
                            onBack              = onBack,
                            initialShowSettings    = screen.openSettings,
                            initialSettingsSection = screen.settingsSection,
                            onOpenVersions         = { fromSettings ->
                                // Coming from the settings overlay: stamp the current
                                // stack entry so Back restores the overlay, and the
                                // section it was standing on. Without the section the
                                // overlay came back on its first one, which is not
                                // where anybody left it -- the version screen is only
                                // reachable from Version.
                                if (fromSettings) {
                                    onReplaceScreen(
                                        Screen.PackDetail(
                                            screen.instanceId,
                                            openSettings = true,
                                            settingsSection = PackSettingsCategory.Version,
                                        ),
                                    )
                                }
                                onScreenChange(Screen.PackVersions(screen.instanceId))
                            },
                        )

                    is Screen.PackVersions ->
                        PackVersionsScreen(
                            instanceId = screen.instanceId,
                            onBack     = onBack,
                        )
                }
            }
    } // end centerBody

    val shellCtx = ShellContext(
        currentScreen   = currentScreen,
        isAuthenticated = appState is AppState.Authenticated,
        onScreenChange  = onScreenChange,
        onLogout        = onLogout,
        appState        = appState,
        onLogin         = onLogin,
        sslBypass       = sslBypass,
        centerBody      = centerBody,
        trail           = trail,
        canGoBack       = canGoBack,
        canGoForward    = canGoForward,
        onBack          = onBack,
        onForward       = onForward,
        onPopTo         = onPopTo,
    )

    // The editor host wraps the WHOLE shell (rails included) so its decorators
    // reach rail widgets; the insets keep the chrome over the center pane (past
    // the 64dp rail + 264dp panel, each plus a 1dp divider). The shell itself is
    // now a widget surface: appshell.root lays its three region widgets in a Row.
    EditorSurfaceHost(
        currentScreen          = currentScreen,
        homeView               = homeView,
        customization          = customization,
        onCustomizationChanged = onCustomizationChanged,
        uiStyle                = uiStyle,
        onUiStyleChanged       = onUiStyleChanged,
        centerStartInset       = 65.dp,
        centerEndInset         = 265.dp,
    ) {
        CompositionLocalProvider(LocalShellContext provides shellCtx) {
            SlotRenderer(
                surface  = SurfaceId("appshell.root"),
                slot     = SlotId("regions"),
                modifier = Modifier.fillMaxSize().background(rowBackground),
            )
        }
    }
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────

@Composable
fun AppSidebar(
    currentScreen: Screen,
    isAuthenticated: Boolean,
    onScreenChange: (Screen) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier.width(64.dp).fillMaxHeight(),
) {
    val gameConsole: GameConsoleService = koinInject()

    // Puppet: sidebar navigation. Direct onScreenChange calls keep
    // test runs deterministic regardless of the AprilFools chaos
    // wrapper inside the nav-buttons widget.
    PuppetClick("nav.home")     { onScreenChange(Screen.Home) }
    PuppetClick("nav.library")  { onScreenChange(Screen.Library) }
    PuppetClick("nav.browse")   { onScreenChange(Screen.Browse) }
    PuppetClick("nav.profile") { onScreenChange(Screen.Profile) }
    PuppetClick("nav.wardrobe") { onScreenChange(Screen.Wardrobe) }
    PuppetClick("nav.settings") { onScreenChange(Screen.Settings) }
    PuppetClick("nav.about")    { onScreenChange(Screen.About) }
    PuppetClick("nav.console")  {
        if (gameConsole.shouldShowConsole) gameConsole.hide()
        else gameConsole.show()
    }
    if (isAuthenticated) {
        PuppetClick("nav.logout") { onLogout() }
    }

    val ctx = remember(currentScreen, isAuthenticated, onScreenChange, onLogout) {
        LeftRailContext(
            currentScreen   = currentScreen,
            isAuthenticated = isAuthenticated,
            onScreenChange  = onScreenChange,
            onLogout        = onLogout,
        )
    }
    CompositionLocalProvider(LocalLeftRailContext provides ctx) {
        NavigationRail(
            modifier       = modifier,
            // Transparent: the rail's NxSurface wrapper (ShellLeftRegion) owns the
            // background now, so its frostTier drives the matte.
            containerColor = Color.Transparent,
            contentColor   = NxTheme.colors.textSecondary
        ) {
            // Items sit flush (spacing 0) so the rail is one contiguous column
            // of clickable slots with no dead gap between buttons. Each NavSlot
            // is taller than its icon and centers it, so the breathing room is
            // the slot's own padding -- and stays clickable. No leading spacer
            // for the same reason; fillMaxWidth keeps the slot's Column at rail
            // width so items center.
            SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("top"), Modifier.fillMaxWidth(), spacing = 0.dp)
            Spacer(Modifier.weight(1f))
            SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("bottom"), Modifier.fillMaxWidth(), spacing = 0.dp)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val SIDEBAR_SURFACE = "appshell.leftrail"

/**
 * Renders [content] once the roster entry behind a server-scoped route resolves.
 *
 * A server that is no longer on the roster leaves rather than paints a screen
 * built on an entry nothing serves any more -- the same exit the version manager
 * takes when its instance is deleted underneath it.
 */
@Composable
private fun WithServer(
    serverId: String,
    onBack: () -> Unit,
    content: @Composable (ServerProfile) -> Unit,
) {
    when (val resolution = rememberServerResolution(serverId)) {
        ServerResolution.Loading -> ContentLoadingPlaceholder()
        ServerResolution.NotFound -> {
            LaunchedEffect(serverId) { onBack() }
            ContentLoadingPlaceholder()
        }
        is ServerResolution.Ready -> content(resolution.server)
    }
}

// ─── Loading placeholder ──────────────────────────────────────────────────────

@Composable
private fun ContentLoadingPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = NxTheme.colors.primary.copy(alpha = 0.35f),
            modifier    = Modifier.size(28.dp),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ContentLoginRequiredPlaceholder(onSignIn: () -> Unit) {
    val s = hivens.ui.i18n.LocalStrings.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Symbol(icon = NxIcon.Person,
                contentDescription = null,
                tint = NxTheme.colors.primary.copy(alpha = 0.45f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = s.dashboardLoginRequiredTitle,
                style = MaterialTheme.typography.titleMedium,
                color = NxTheme.colors.textPrimary
            )
            Text(
                text = s.dashboardLoginRequiredHint,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
            NxButton(
                label   = s.loginButton,
                onClick = onSignIn,
                style   = NxButtonStyle.Primary,
            )
        }
    }
}
