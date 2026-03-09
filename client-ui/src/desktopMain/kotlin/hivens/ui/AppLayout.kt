package hivens.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.ui.i18n.AppLocale
import hivens.ui.screens.*
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.utils.GameConsoleService
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
    onLocaleChanged: (AppLocale) -> Unit
) {
    val skinRepository: SkinRepository = koinInject()

    // Session can be refreshed by DashboardScreen on auth-retry
    var currentSession by remember(appState) {
        mutableStateOf((appState as? AppState.Authenticated)?.session)
    }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }

    Row(Modifier.fillMaxSize().background(CelestiaTheme.colors.background)) {

        // ── Sidebar 52dp ──────────────────────────────────────────────────────
        AppSidebar(
            currentScreen   = currentScreen,
            isAuthenticated = appState is AppState.Authenticated,
            onScreenChange  = onScreenChange,
            onLogout        = onLogout
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color    = CelestiaTheme.colors.surface.copy(alpha = 0.6f)
        )

        // ── Main content ──────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Crossfade(
                targetState   = currentScreen,
                animationSpec = tween(180)) { screen ->
                when (screen) {
                    Screen.Home -> {
                        val session = currentSession
                        if (session != null) {
                            DashboardScreen(
                                session               = session,
                                initialSelectedServer = selectedServer,
                                onServerSelected      = { selectedServer = it },
                                onSessionUpdated      = { currentSession = it },
                                onCloseApp            = onCloseApp,
                                onOpenServerSettings  = { onScreenChange(Screen.ServerSettings(it)) },
                                onOpenNews            = { onScreenChange(Screen.News) },
                                onOpenDetails         = { onScreenChange(Screen.ServerDetails(it)) }
                            )
                        } else {
                            ContentLoadingPlaceholder()
                        }
                    }

                    Screen.News ->
                        NewsScreen(onBack = { onScreenChange(Screen.Home) })

                    Screen.Profile ->
                        currentSession?.let {
                            ProfileScreen(session = it, skinRepository = skinRepository)
                        }

                    Screen.Settings ->
                        SettingsScreen(
                            isDarkTheme       = isDarkTheme,
                            onToggleTheme     = onToggleDarkTheme,
                            onOpenThemePicker = { onScreenChange(Screen.ThemePicker) },
                            currentLocale     = currentLocale,
                            onLocaleChanged   = onLocaleChanged
                        )

                    Screen.ThemePicker ->
                        ThemePickerScreen(
                            currentTheme    = customTheme,
                            onThemeSelected = { newTheme ->
                                onCustomThemeChanged(newTheme)
                                onScreenChange(Screen.Settings)
                            },
                            onBack = { onScreenChange(Screen.Settings) }
                        )

                    is Screen.ServerSettings ->
                        ServerSettingsScreen(
                            server = screen.server,
                            onBack = { onScreenChange(Screen.Home) }
                        )

                    is Screen.ServerDetails ->
                        ServerDetailScreen(
                            server = screen.server,
                            onBack = { onScreenChange(Screen.Home) }
                        )
                }
            }
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color    = CelestiaTheme.colors.surface.copy(alpha = 0.6f)
        )

        // ── Right panel 264dp ─────────────────────────────────────────────────
        RightPanel(
            appState = appState,
            onLogin  = onLogin,
            onLogout = onLogout,
            modifier = Modifier.width(264.dp).fillMaxHeight()
        )
    }
}

// ─── Sidebar — M3 NavigationRail ─────────────────────────────────────────────

@Composable
fun AppSidebar(
    currentScreen: Screen,
    isAuthenticated: Boolean,
    onScreenChange: (Screen) -> Unit,
    onLogout: () -> Unit
) {
    val homeActive = currentScreen is Screen.Home
            || currentScreen is Screen.ServerSettings
            || currentScreen is Screen.ServerDetails
            || currentScreen is Screen.News
    val profileActive  = currentScreen is Screen.Profile
    val settingsActive = currentScreen is Screen.Settings || currentScreen is Screen.ThemePicker

    NavigationRail(
        modifier       = Modifier.width(64.dp).fillMaxHeight(),
        containerColor = CelestiaTheme.colors.surface.copy(alpha = 0.35f),
        contentColor   = CelestiaTheme.colors.textSecondary
    ) {
        // ── Nav items ─────────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))

        SidebarNavItem(
            icon     = Icons.Default.Home,
            selected = homeActive,
            onClick  = { onScreenChange(Screen.Home) }
        )
        SidebarNavItem(
            icon     = Icons.Default.Person,
            selected = profileActive,
            enabled  = isAuthenticated,
            onClick  = { onScreenChange(Screen.Profile) }
        )
        SidebarNavItem(
            icon     = Icons.Default.Settings,
            selected = settingsActive,
            onClick  = { onScreenChange(Screen.Settings) }
        )

        // ── Bottom actions ────────────────────────────────────────────────
        Spacer(Modifier.weight(1f))

        IconButton(
            onClick  = {
                if (GameConsoleService.shouldShowConsole) GameConsoleService.hide()
                else GameConsoleService.show()
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Build,
                contentDescription = null,
                tint               = if (GameConsoleService.shouldShowConsole)
                    CelestiaTheme.colors.primary
                else
                    CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
                modifier           = Modifier.size(22.dp)
            )
        }

        if (isAuthenticated) {
            IconButton(onClick = onLogout, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.error.copy(alpha = 0.75f),
                    modifier           = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ─── NavigationRailItem wrapper (icon-only, no label) ────────────────────────

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    NavigationRailItem(
        icon = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                modifier           = Modifier.size(24.dp)
            )
        },
        selected        = selected,
        onClick         = onClick,
        enabled         = enabled,
        label           = null,
        alwaysShowLabel = false,
        colors          = NavigationRailItemDefaults.colors(
            selectedIconColor   = CelestiaTheme.colors.primary,
            unselectedIconColor = CelestiaTheme.colors.textSecondary.copy(
                alpha = if (enabled) 0.70f else 0.20f
            ),
            indicatorColor      = CelestiaTheme.colors.primary.copy(alpha = 0.13f)
        )
    )
}

// ─── Loading placeholder ──────────────────────────────────────────────────────

@Composable
private fun ContentLoadingPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color        = CelestiaTheme.colors.primary.copy(alpha = 0.35f),
            modifier     = Modifier.size(28.dp),
            strokeWidth  = 2.dp
        )
    }
}
