package hivens.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.model.ServerProfile
import hivens.core.data.HomeView
import hivens.core.data.SessionData
import hivens.core.data.UiStyle
import hivens.launcher.network.NetworkState
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.background.BackgroundSettings
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.AppLocale
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.screens.browse.BrowseScreen
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.screens.library.LibraryScreen
import hivens.ui.widgets.profile.ProfileSurface
import hivens.ui.screens.settings.SettingsScreen
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.serverdetails.ServerDetailsSurface
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.CustomTheme
import hivens.ui.editor.EditorSurfaceHost
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.shell.LeftRailContext
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject

// ─── Layout ──────────────────────────────────────────────────────────────────

@Composable
fun AppLayout(
    appState: AppState,
    onCloseApp: () -> Unit,
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
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

    // When custom background is active, make the row transparent so image shows through
    val rowBackground = if (backgroundSettings.enabled) Color.Transparent
    else CelestiaTheme.colors.background

    val bypassHost = protocolConfig.sslBypassHost
    val bypassesList by NetworkState.bypassesState.collectAsState()
    val sslBypass = remember(bypassesList, bypassHost) { NetworkState.bypassFor(bypassHost) }

    Row(Modifier.fillMaxSize().background(rowBackground)) {

        // ── Sidebar 64dp ──────────────────────────────────────────────────
        AppSidebar(
            currentScreen   = currentScreen,
            isAuthenticated = appState is AppState.Authenticated,
            onScreenChange  = onScreenChange,
            onLogout        = onLogout
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color    = glassSurfaceAlpha(0.6f)
        )

        // ── Main content ──────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight()) {
          EditorSurfaceHost(
              currentScreen          = currentScreen,
              homeView               = homeView,
              customization          = customization,
              onCustomizationChanged = onCustomizationChanged,
              uiStyle                = uiStyle,
              onUiStyleChanged       = onUiStyleChanged,
          ) {
            // Screen-to-screen Crossfade duration follows the active
            // style. Under Brut (animationMultiplier = 0) the swap is
            // effectively instant; under Celestia keeps the 180ms fade.
            val crossfadeMs = LocalStyle.current.animationDurationMs(180)
            Crossfade(
                targetState   = currentScreen,
                animationSpec = tween(crossfadeMs)
            ) { screen ->
                when (screen) {
                    Screen.Home -> {
                        val session = currentSession
                        when {
                            session != null -> when (homeView) {
                                HomeView.Classic -> DashboardScreen(
                                    session               = session,
                                    initialSelectedServer = selectedServer,
                                    onServerSelected      = { selectedServer = it },
                                    onSessionUpdated      = { currentSession = it },
                                    onCloseApp            = onCloseApp,
                                    onOpenServerSettings  = { onScreenChange(Screen.ServerSettings(it)) },
                                    onOpenDetails         = { onScreenChange(Screen.ServerDetails(it)) }
                                )
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
                            // `Loading` is the brief window between startup and resolved
                            // credentials -- spinner is appropriate. `Unauthenticated` is
                            // a stable state waiting on user input; show the explicit
                            // "sign in" copy so the spinning placeholder doesn't read as
                            // a stuck network request.
                            appState is AppState.Loading -> ContentLoadingPlaceholder()
                            else -> ContentLoginRequiredPlaceholder()
                        }
                    }

                    Screen.Profile ->
                        currentSession?.let {
                            ProfileSurface(session = it)
                        }

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
                            onOpenCustomizationExtension = { onScreenChange(Screen.CustomizationExtension) },
                            onOpenAbout                  = { onScreenChange(Screen.About) },
                        )

                    Screen.ThemePicker ->
                        ThemePickerSurface(
                            currentTheme    = customTheme,
                            onThemeSelected = { newTheme ->
                                onCustomThemeChanged(newTheme)
                                onScreenChange(Screen.Settings)
                            },
                            onBack          = { onScreenChange(Screen.Settings) },
                        )

                    Screen.CustomizationExtension ->
                        hivens.ui.screens.customization.CustomizationExtensionScreen(
                            currentSettings   = customization,
                            onSettingsChanged = onCustomizationChanged,
                            onBack            = { onScreenChange(Screen.Settings) },
                        )

                    Screen.About ->
                        AboutSurface(onBack = { onScreenChange(Screen.Settings) })

                    Screen.BackgroundSettings ->
                        BackgroundSettingsScreen(
                            currentSettings   = backgroundSettings,
                            onSettingsChanged = onBackgroundSettingsChanged,
                            onBack            = { onScreenChange(Screen.Settings) }
                        )

                    is Screen.ServerSettings ->
                        ServerSettingsScreen(
                            server = screen.server,
                            onBack = { onScreenChange(Screen.Home) }
                        )

                    is Screen.ServerDetails ->
                        ServerDetailsSurface(
                            server = screen.server,
                            onBack = { onScreenChange(Screen.Home) },
                        )

                    Screen.Library -> LibraryScreen(
                        appState       = appState,
                        onScreenChange = onScreenChange,
                    )

                    Screen.Browse  -> BrowseScreen(
                        onOpenPack = { packId -> onScreenChange(Screen.BrowsePackDetail(packId)) },
                    )

                    is Screen.BrowsePackDetail ->
                        hivens.ui.screens.browse.BrowsePackDetailScreen(
                            packId      = screen.packId,
                            onBack      = { onScreenChange(Screen.Browse) },
                            onInstalled = { instanceId -> onScreenChange(Screen.PackDetail(instanceId)) },
                        )

                    is Screen.PackDetail ->
                        PackDetailScreen(
                            instanceId = screen.instanceId,
                            appState   = appState,
                            onBack     = { onScreenChange(Screen.Library) },
                        )
                }
            }
          } // end EditorSurfaceHost
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color    = glassSurfaceAlpha(0.6f)
        )

        // ── Right panel 264dp ─────────────────────────────────────────────
        RightPanel(
            appState = appState,
            onLogin  = onLogin,
            onLogout = onLogout,
            sslBypass = sslBypass,
            modifier = Modifier.width(264.dp).fillMaxHeight()
        )
    }
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────

@Composable
fun AppSidebar(
    currentScreen: Screen,
    isAuthenticated: Boolean,
    onScreenChange: (Screen) -> Unit,
    onLogout: () -> Unit
) {
    val gameConsole: GameConsoleService = koinInject()

    // Puppet: sidebar navigation. Direct onScreenChange calls keep
    // test runs deterministic regardless of the AprilFools chaos
    // wrapper inside the nav-buttons widget.
    PuppetClick("nav.home")     { onScreenChange(Screen.Home) }
    PuppetClick("nav.library")  { onScreenChange(Screen.Library) }
    PuppetClick("nav.browse")   { onScreenChange(Screen.Browse) }
    PuppetClick("nav.profile", enabled = isAuthenticated) { onScreenChange(Screen.Profile) }
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
            modifier       = Modifier.width(64.dp).fillMaxHeight(),
            containerColor = glassSurfaceAlpha(0.35f),
            contentColor   = CelestiaTheme.colors.textSecondary
        ) {
            SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("top"))
            // Spacer is layout, not content -- stays surface-owned so
            // widgets in the top/bottom slots do not need ColumnScope
            // for weight.
            Spacer(Modifier.weight(1f))
            SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("bottom"))
            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val SIDEBAR_SURFACE = "appshell.leftrail"

// ─── Loading placeholder ──────────────────────────────────────────────────────

@Composable
private fun ContentLoadingPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = CelestiaTheme.colors.primary.copy(alpha = 0.35f),
            modifier    = Modifier.size(28.dp),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ContentLoginRequiredPlaceholder() {
    val s = hivens.ui.i18n.LocalStrings.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = CelestiaTheme.colors.primary.copy(alpha = 0.45f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = s.dashboardLoginRequiredTitle,
                style = MaterialTheme.typography.titleMedium,
                color = CelestiaTheme.colors.textPrimary
            )
            Text(
                text = s.dashboardLoginRequiredHint,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }
}
