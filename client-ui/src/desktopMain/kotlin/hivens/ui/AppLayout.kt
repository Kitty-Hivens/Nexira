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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.network.NetworkState
import hivens.ui.background.BackgroundSettings
import hivens.ui.easter.AprilFools
import hivens.ui.i18n.AppLocale
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

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
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    onBackgroundSettingsChanged: (BackgroundSettings) -> Unit = {}
) {
    val skinRepository: SkinRepository = koinInject()
    val protocolConfig: hivens.launcher.network.ServerProtocolConfig = koinInject()

    // Session can be refreshed by DashboardScreen on auth-retry
    var currentSession by remember(appState) {
        mutableStateOf((appState as? AppState.Authenticated)?.session)
    }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }

    // When custom background is active, make the row transparent so image shows through
    val rowBackground = if (backgroundSettings.enabled) Color.Transparent
    else CelestiaTheme.colors.background

    val bypassHost = protocolConfig.sslBypassHost
    val sslBypass by produceState(initialValue = NetworkState.bypassFor(bypassHost), bypassHost) {
        while (true) {
            value = NetworkState.bypassFor(bypassHost)
            delay(200.milliseconds)
        }
    }

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
            color    = CelestiaTheme.colors.surface.copy(alpha = 0.6f)
        )

        // ── Main content ──────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Crossfade(
                targetState   = currentScreen,
                animationSpec = tween(180)
            ) { screen ->
                when (screen) {
                    Screen.Home -> {
                        val session = currentSession
                        when {
                            session != null -> DashboardScreen(
                                session               = session,
                                initialSelectedServer = selectedServer,
                                onServerSelected      = { selectedServer = it },
                                onSessionUpdated      = { currentSession = it },
                                onCloseApp            = onCloseApp,
                                onOpenServerSettings  = { onScreenChange(Screen.ServerSettings(it)) },
                                onOpenDetails         = { onScreenChange(Screen.ServerDetails(it)) }
                            )
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
                            ProfileScreen(session = it, skinRepository = skinRepository)
                        }

                    Screen.Settings ->
                        SettingsScreen(
                            isDarkTheme              = isDarkTheme,
                            onToggleTheme            = onToggleDarkTheme,
                            onOpenThemePicker        = { onScreenChange(Screen.ThemePicker) },
                            currentLocale            = currentLocale,
                            onLocaleChanged          = onLocaleChanged,
                            onOpenBackgroundSettings = { onScreenChange(Screen.BackgroundSettings) },
                            onOpenAbout              = { onScreenChange(Screen.About) }
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

                    Screen.About ->
                        AboutScreen(
                            onBack = { onScreenChange(Screen.Settings) }
                        )

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
    val homeActive = currentScreen is Screen.Home
            || currentScreen is Screen.ServerSettings
            || currentScreen is Screen.ServerDetails
    val profileActive  = currentScreen is Screen.Profile
    val settingsActive = currentScreen is Screen.Settings
            || currentScreen is Screen.ThemePicker
            || currentScreen is Screen.BackgroundSettings
    val aboutActive    = currentScreen is Screen.About

    // ── April Fools: nav clicks have a 30% chance of being silently swallowed ──
    // The button doesn't move or react -- it just feels like the UI froze.
    // Logout is intentionally excluded so the user can always escape.
    fun chaosNavClick(originalClick: () -> Unit): () -> Unit {
        if (!AprilFools.isActive()) return originalClick
        return {
            if (Random.nextFloat() > 0.30f) {
                originalClick()
            }
            // else: click silently consumed -- simulates UI "lag"
        }
    }

    // ── April Fools: bouncing nav buttons ────────────────────────────────────
    // Each item has a unique sine phase so they bounce out of sync.
    // Amplitude grows from 0px on day 1 to 18px on day 14.
    val bounceAmplitude = if (AprilFools.isActive()) AprilFools.intensity() * 18f else 0f

    val bounceTransition = rememberInfiniteTransition(label = "navBounce")
    val bounceCycle by bounceTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (AprilFools.isActive())
                    (2200 - AprilFools.intensity() * 1400).toInt().coerceAtLeast(600)
                else
                    2200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "bounceCycle"
    )

    // Different phase offset per button -- they never all peak at the same time
    val homeOffset    = sin(bounceCycle + 0.0f) * bounceAmplitude
    val profileOffset = sin(bounceCycle + 1.1f) * bounceAmplitude
    val settingsOffset= sin(bounceCycle + 2.2f) * bounceAmplitude
    val aboutOffset   = sin(bounceCycle + 3.3f) * bounceAmplitude

    // Puppet: sidebar navigation. Puppet driver bypasses the AprilFools
    // chaos wrapper -- those are user-facing pranks, not behavior we want
    // to test against. Direct onScreenChange calls keep test runs
    // deterministic regardless of the calendar.
    PuppetClick("nav.home")     { onScreenChange(Screen.Home) }
    PuppetClick("nav.profile", enabled = isAuthenticated) { onScreenChange(Screen.Profile) }
    PuppetClick("nav.settings") { onScreenChange(Screen.Settings) }
    PuppetClick("nav.about")    { onScreenChange(Screen.About) }
    PuppetClick("nav.console")  {
        if (GameConsoleService.shouldShowConsole) GameConsoleService.hide()
        else GameConsoleService.show()
    }
    if (isAuthenticated) {
        PuppetClick("nav.logout") { onLogout() }
    }

    NavigationRail(
        modifier       = Modifier.width(64.dp).fillMaxHeight(),
        containerColor = CelestiaTheme.colors.surface.copy(alpha = 0.35f),
        contentColor   = CelestiaTheme.colors.textSecondary
    ) {
        // ── Nav items ─────────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))

        // Each item wrapped in a Box that applies the vertical bounce offset
        Box(Modifier.graphicsLayer { translationY = homeOffset }) {
            SidebarNavItem(
                icon     = Icons.Default.Home,
                selected = homeActive,
                onClick  = chaosNavClick { onScreenChange(Screen.Home) }
            )
        }

        Box(Modifier.graphicsLayer { translationY = profileOffset }) {
            SidebarNavItem(
                icon     = Icons.Default.Person,
                selected = profileActive,
                enabled  = isAuthenticated,
                onClick  = chaosNavClick { onScreenChange(Screen.Profile) }
            )
        }

        Box(Modifier.graphicsLayer { translationY = settingsOffset }) {
            SidebarNavItem(
                icon     = Icons.Default.Settings,
                selected = settingsActive,
                onClick  = chaosNavClick { onScreenChange(Screen.Settings) }
            )
        }

        Box(Modifier.graphicsLayer { translationY = aboutOffset }) {
            SidebarNavItem(
                icon     = Icons.Default.Info,
                selected = aboutActive,
                onClick  = chaosNavClick { onScreenChange(Screen.About) }
            )
        }

        // ── Bottom actions ────────────────────────────────────────────────
        Spacer(Modifier.weight(1f))

        // Console toggle -- not bouncing, user needs to be able to open it
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

        // Logout -- NOT chaos-wrapped, NOT bouncing -- user must always be able to log out
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }
}
