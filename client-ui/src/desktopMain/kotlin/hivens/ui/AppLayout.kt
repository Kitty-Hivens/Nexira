package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.core.security.SslBypassStore
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.hasUsableImage
import hivens.ui.customization.CustomizationSettings
import hivens.ui.editor.EditorSurfaceHost
import hivens.ui.i18n.AppLocale
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.bgsettings.BgSettingsSurface
import hivens.ui.widgets.profile.ProfileSurface
import hivens.ui.widgets.wardrobe.WardrobeSurface
import hivens.ui.widgets.shell.LeftRailContext
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalShellContext
import hivens.ui.widgets.shell.ShellContext
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.ui.screens.mod.LocalLinkFollower
import hivens.ui.screens.mod.ModDetailScreen
import hivens.ui.screens.mod.rememberNavigatingLinkFollower
import hivens.ui.screens.mod.ModVersionScreen
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
    onSwitchTab: (Screen) -> Unit,
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
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    onBackgroundSettingsChanged: (BackgroundSettings) -> Unit = {},
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
) {
    val protocolConfig: ServerProtocolConfig = koinInject()

    // Whoever signs in reports it upward, and the shell's own state is what comes
    // back down here -- so this is derived rather than held. It used to be a var
    // the classic home wrote into on an SC re-auth, which meant a refresh survived
    // exactly until the next appState change discarded it.
    val currentSession = (appState as? AppState.Authenticated)?.session

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
    // invokes it. Reads currentSession live on each recompose.
    val centerBody: @Composable () -> Unit = {
        // Screens are disposed when they are swapped out, so everything a reader had
        // arranged -- a scroll position, an open tab, a chosen category -- died with
        // the visit and came back at its default. Held per destination here instead.
        val retention = rememberSaveableStateHolder()
        // How a screen replaces another is the swap primitive's business, not the
        // router's. A still style collapses it without this site knowing.
        NxSwap(
            target = currentScreen,
            label  = "screen",
        ) { screen ->
            retention.SaveableStateProvider(screen.retentionKey) {
                when (screen) {
                    // One home. The classic dashboard WAS the SmartyCraft server
                    // list, and SmartyCraft arrives as mirror packs now, so the
                    // screen it lived on went with the path it was a front for.
                    Screen.Home -> NewHomeScreen(
                        appState       = appState,
                        onScreenChange = onScreenChange,
                    )

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
                            surfaceBlur       = customization.surfaceBlur,
                            onSurfaceBlurChanged = { onCustomizationChanged(customization.copy(surfaceBlur = it)) },
                            onOpenThemePicker = { onScreenChange(Screen.ThemePicker) },
                        )

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
                            onOpenProject          = { onScreenChange(Screen.ModDetail(it)) },
                        )

                    is Screen.PackVersions ->
                        PackVersionsScreen(
                            instanceId = screen.instanceId,
                            onBack     = onBack,
                        )

                    is Screen.ModDetail -> ModDetailScreen(
                        target = screen.target,
                        onOpenVersion = { id, number ->
                            onScreenChange(Screen.ModVersion(screen.target, id, number))
                        },
                    )

                    is Screen.ModVersion -> ModVersionScreen(
                        target = screen.target,
                        versionId = screen.versionId,
                    )
                }
            }
        }
    } // end centerBody

    val shellCtx = ShellContext(
        currentScreen   = currentScreen,
        isAuthenticated = appState is AppState.Authenticated,
        onScreenChange  = onScreenChange,
        onSwitchTab     = onSwitchTab,
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
    // reach rail widgets; it puts its own chrome back over the content pane from
    // what the centre region reports. The shell itself is a widget surface:
    // appshell.root lays its three region widgets in a Row.
    EditorSurfaceHost(
        currentScreen          = currentScreen,
        customization          = customization,
        onCustomizationChanged = onCustomizationChanged,
    ) {
        // Links that point at a project the launcher can draw stop going out to a
        // browser from here down. Provided at the shell rather than per surface, so
        // a markdown body and a rail widget follow the same rule.
        CompositionLocalProvider(
            LocalShellContext provides shellCtx,
            LocalLinkFollower provides rememberNavigatingLinkFollower(),
        ) {
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
    onSwitchTab: (Screen) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier.width(64.dp).fillMaxHeight(),
) {
    val gameConsole: GameConsoleService = koinInject()

    // Puppet: sidebar navigation. Direct onScreenChange calls keep
    // test runs deterministic regardless of the AprilFools chaos
    // wrapper inside the nav-buttons widget.
    PuppetClick("nav.home")     { onSwitchTab(Screen.Home) }
    PuppetClick("nav.library")  { onSwitchTab(Screen.Library) }
    PuppetClick("nav.browse")   { onSwitchTab(Screen.Browse) }
    PuppetClick("nav.profile") { onSwitchTab(Screen.Profile) }
    PuppetClick("nav.wardrobe") { onSwitchTab(Screen.Wardrobe) }
    PuppetClick("nav.settings") { onSwitchTab(Screen.Settings) }
    PuppetClick("nav.about")    { onSwitchTab(Screen.About) }
    PuppetClick("nav.console")  {
        if (gameConsole.shouldShowConsole) gameConsole.hide()
        else gameConsole.show()
    }
    if (isAuthenticated) {
        PuppetClick("nav.logout") { onLogout() }
    }

    val ctx = remember(currentScreen, isAuthenticated, onScreenChange, onSwitchTab, onLogout) {
        LeftRailContext(
            currentScreen   = currentScreen,
            isAuthenticated = isAuthenticated,
            onScreenChange  = onScreenChange,
            onSwitchTab     = onSwitchTab,
            onLogout        = onLogout,
        )
    }
    CompositionLocalProvider(LocalLeftRailContext provides ctx) {
        NavigationRail(
            modifier       = modifier,
            // Transparent: the rail's NxSurface wrapper (ShellLeftRegion) owns the
            // background now, so its own opacity and blur drive the matte.
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
