package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.core.data.ThemeMode
import hivens.ui.background.BackgroundSettings
import hivens.ui.chrome.LocalWindowClass
import hivens.ui.chrome.rememberWindowClass
import hivens.ui.background.hasUsableImage
import hivens.ui.customization.CustomizationSettings
import hivens.ui.editor.EditorSurfaceHost
import hivens.ui.i18n.LocalAppLocale
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import hivens.ui.nx.NxSwap
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.*
import hivens.ui.feature.catalogue.browse.BrowseController
import hivens.ui.feature.catalogue.browse.BrowseScreen
import hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen
import hivens.ui.feature.catalogue.browse.LocalCatalogueSearch
import hivens.ui.feature.catalogue.browse.rememberShellCatalogueSearch
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.ui.screens.detail.versions.PackVersionsScreen
import hivens.ui.screens.library.LibraryScreen
import hivens.ui.screens.settings.SettingsScreen
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.Plane
import hivens.ui.theme.Backdrop
import hivens.ui.theme.LocalPlane
import hivens.ui.utils.releaseFocusOnPress
import hivens.ui.widgets.about.AboutSurface
import hivens.ui.widgets.bgsettings.BgSettingsSurface
import hivens.ui.widgets.profile.ProfileSurface
import hivens.ui.widgets.wardrobe.WardrobeSurface
import hivens.ui.widgets.shell.LocalCenterBody
import hivens.ui.widgets.themepicker.ThemePickerSurface
import hivens.ui.feature.catalogue.project.LocalLinkFollower
import hivens.ui.feature.catalogue.project.ModDetailScreen
import hivens.ui.feature.catalogue.project.rememberNavigatingLinkFollower
import hivens.ui.feature.catalogue.project.ModVersionScreen
import hivens.ui.screens.custom.CustomScreen
import hivens.ui.navigation.NavState
import hivens.ui.widgets.Commands
import hivens.ui.widgets.LevelHost
import hivens.ui.widgets.MisplacedWidget
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.PageLevels
import hivens.widget.api.ProvideScoped
import hivens.widget.api.ProvideScopedCommand
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberLatestCommand
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

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
    history: List<Screen> = trail,
    onPopTo: (Screen) -> Unit,
    backgroundSettings: BackgroundSettings = BackgroundSettings(),
    onBackgroundSettingsChanged: (BackgroundSettings.() -> BackgroundSettings) -> Unit = {},
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
) {
    val windowClass = rememberWindowClass()
    val browse: BrowseController = koinInject()

    // How the composition looks and speaks, read and changed the way a widget does,
    // for the screens that still take it as arguments.
    val isDarkTheme = rememberScopedSource(Sources.DarkTheme).valueOrNull() ?: true
    val themeMode = rememberScopedSource(Sources.ThemeMode).valueOrNull() ?: ThemeMode.Manual
    val systemThemeAvailable = rememberScopedSource(Sources.SystemSchemeAvailable).valueOrNull() ?: false
    val themeLibrary = rememberScopedSource(Sources.ThemeLibrary).valueOrNull() ?: ThemeLibrary()
    val currentLocale = rememberScopedSource(Sources.Locale).valueOrNull() ?: LocalAppLocale.current
    val setDark = rememberScopedCommand(Commands.SetDarkTheme)
    val onToggleDarkTheme: () -> Unit = { setDark?.invoke(!isDarkTheme) }
    val onThemeModeChanged = rememberScopedCommand(Commands.SetThemeMode) ?: {}
    val onLocaleChanged = rememberScopedCommand(Commands.SetLocale) ?: {}

    // Whoever signs in reports it upward, and the shell's own state is what comes
    // back down here -- so this is derived rather than held. It used to be a var
    // the classic home wrote into on an SC re-auth, which meant a refresh survived
    // exactly until the next appState change discarded it.
    val currentSession = (appState as? AppState.Authenticated)?.session

    // Go transparent only when the wallpaper can actually be drawn: a deleted image
    // left the row transparent over a blank white window. Everything inside is told
    // which of the two it sits on, so a plane over a picture knows it is over one.
    val overWallpaper = backgroundSettings.hasUsableImage()
    val rowBackground = if (overWallpaper) Color.Transparent else NxColor.page
    val page = Plane(step = 0, color = NxColor.page, over = if (overWallpaper) Backdrop.Wallpaper else Backdrop.Page)

    // The center region's screen router. Defined here (not in the layout graph)
    // because navigation is not yet a widget surface; the center region widget
    // invokes it. Reads currentSession live on each recompose.
    val centerBody: @Composable () -> Unit = {
        // Screens are disposed when they are swapped out, so everything a reader had
        // arranged -- a scroll position, an open tab, a chosen category -- died with
        // the visit and came back at its default. Held per destination here instead.
        val retention = rememberSaveableStateHolder()
        // And a page level per destination, for what a page makes and is handed. It
        // lives while the destination is anywhere in the history, back or forward,
        // and goes once it has left the history and stopped drawing. The composition's
        // level keeps them, so they go with it even if this router leaves first.
        val pages = LocalScope.current?.nearest(ScopeLevel.Composition)?.pages
        val historyKeys = history.mapTo(HashSet()) { it.retentionKey }
        LaunchedEffect(pages, historyKeys) { pages?.keep(historyKeys) }
        // How a screen replaces another is the swap primitive's business, not the
        // router's. A still style collapses it without this site knowing.
        NxSwap(
            target = currentScreen,
            label  = "screen",
        ) { screen ->
            Destination(retention, pages, screen.retentionKey) {
                when (screen) {
                    // One home. The classic dashboard WAS the SmartyCraft server
                    // list, and SmartyCraft arrives as mirror packs now, so the
                    // screen it lived on went with the path it was a front for.
                    Screen.Home -> NewHomeScreen()

                    Screen.Profile ->
                        ProfileSurface(
                            session       = currentSession,
                            authResolving = appState is AppState.Loading,
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

                    Screen.ThemePicker -> ThemePickerSurface()

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
                            activeTheme       = themeLibrary.active,
                            surfaceBlur       = customization.surfaceBlur,
                            onSurfaceBlurChanged = { onCustomizationChanged(customization.copy(surfaceBlur = it)) },
                            reduceMotion      = customization.reduceMotion,
                            onReduceMotionChanged = { onCustomizationChanged(customization.copy(reduceMotion = it)) },
                            onOpenThemePicker = { onScreenChange(Screen.ThemePicker) },
                        )

                    Screen.Library -> LibraryScreen(onScreenChange = onScreenChange)

                    Screen.Browse  -> BrowseScreen()

                    is Screen.BrowseInto -> BrowseScreen(intoInstanceId = screen.instanceId)

                    is Screen.CataloguePackDetail ->
                        CataloguePackDetailScreen(
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
                            onFindProjects         = {
                                browse.aimAt(screen.instanceId)
                                onScreenChange(Screen.BrowseInto(screen.instanceId))
                            },
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

                    is Screen.Custom -> CustomScreen(screen.id)
                }
            }
        }
    } // end centerBody

    // What this composition's widgets fire to move its history and read of where it
    // stands, put over the composition's level, which AppRoot made around everything
    // it draws. Around the editor too, so a widget the palette draws reads the same
    // answers as one on a surface, and a widget anywhere in the shell finds them,
    // where a context provided by one screen pinned its widgets to that screen.
    // Everything else a widget reads here, the session and the appearance among it,
    // the level makes for itself.
    ProvideComposition(
        nav          = NavState(currentScreen, trail, canGoBack, canGoForward),
        navigate     = onScreenChange,
        switchTab    = onSwitchTab,
        back         = onBack,
        forward      = onForward,
        popTo        = onPopTo,
    ) {
    // The editor host wraps the WHOLE shell (rails included) so its decorators
    // reach rail widgets; it puts its own chrome back over the content pane from
    // what the centre region reports. The shell itself is a widget surface:
    // appshell.root lays its three region widgets in a Row.
    EditorSurfaceHost(
        currentScreen          = currentScreen,
        customization          = customization,
        onCustomizationChanged = onCustomizationChanged,
        onOpenScreen           = onSwitchTab,
    ) {
        // Links that point at a project the launcher can draw stop going out to a
        // browser from here down, and a project's tags open the catalogue. Provided
        // at the shell rather than per surface, so a markdown body, a page header and
        // a rail widget follow the same rule.
        CompositionLocalProvider(
            LocalCenterBody provides centerBody,
            LocalLinkFollower provides rememberNavigatingLinkFollower(),
            LocalCatalogueSearch provides rememberShellCatalogueSearch(currentScreen, onScreenChange),
            LocalPlane provides page,
            LocalWindowClass provides windowClass,
        ) {
            SlotRenderer(
                surface  = SurfaceId("appshell.root"),
                slot     = SlotId("regions"),
                modifier = Modifier.fillMaxSize().background(rowBackground).releaseFocusOnPress(),
            )
        }
    }
    } // end ProvideComposition
}

/**
 * Puts the composition's history over its level for [content]: where it stands
 * and the commands that move it, the keys only the owner of the history can answer.
 * Each command is one object for the composition's life that runs the latest
 * lambda it was handed, so a reader does not recompose because the shell did.
 *
 * Widgets that cannot stand without one of them draw the placeholder for a widget
 * that stands where something it reads has no answer, which is also put here.
 */
@Composable
private fun ProvideComposition(
    nav: NavState,
    navigate: (Screen) -> Unit,
    switchTab: (Screen) -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    popTo: (Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    val navigateCommand = rememberLatestCommand(navigate)
    val switchTabCommand = rememberLatestCommand(switchTab)
    val backCommand = rememberLatestCommand<Unit> { back() }
    val forwardCommand = rememberLatestCommand<Unit> { forward() }
    val popToCommand = rememberLatestCommand(popTo)
    ProvideScoped(Sources.Navigation, nav) {
    ProvideScopedCommand(Commands.Navigate, navigateCommand) {
    ProvideScopedCommand(Commands.SwitchTab, switchTabCommand) {
    ProvideScopedCommand(Commands.Back, backCommand) {
    ProvideScopedCommand(Commands.Forward, forwardCommand) {
    ProvideScopedCommand(Commands.PopTo, popToCommand) {
        CompositionLocalProvider(LocalMisplacedWidget provides MisplacedWidget, content = content)
    }
    }
    }
    }
    }
    }
}

/**
 * One destination of the router: its saveable state and its page level, both held
 * under [key]. The content holds its level from the moment it composes, so a
 * destination that left the history keeps its level until it has finished fading
 * out, and one that just arrived cannot be swept by another leaving in its first frame.
 */
@Composable
private fun Destination(
    retention: SaveableStateHolder,
    pages: PageLevels?,
    key: String,
    content: @Composable () -> Unit,
) {
    retention.SaveableStateProvider(key) {
        val level = pages?.let {
            val _ = remember(it, key) { it.hold(key) }
            it.levelFor(key)
        }
        LevelHost(level, content)
    }
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────

@Composable
fun AppSidebar(
    isAuthenticated: Boolean,
    onSwitchTab: (Screen) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier.width(64.dp).fillMaxHeight(),
) {
    val consoleVisible = rememberScopedSource(Sources.ConsoleVisible).valueOrNull() == true
    val setConsoleVisible = rememberScopedCommand(Commands.SetConsoleVisible)

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
    PuppetClick("nav.console")  { setConsoleVisible?.invoke(!consoleVisible) }
    if (isAuthenticated) {
        PuppetClick("nav.logout") { onLogout() }
    }
    // A made screen has no fixed entry above, so it is driven by its id.
    LocalLayoutGraph.current.screens.forEach { spec ->
        key(spec.id) { PuppetClick("nav.screen.${spec.id}") { onSwitchTab(Screen.Custom(spec.id)) } }
    }

    NavigationRail(
        modifier       = modifier,
        // Transparent: the rail's NxSurface wrapper (ShellLeftRegion) owns the
        // background now, so its own opacity and blur drive the matte.
        containerColor = Color.Transparent,
        contentColor   = NxInk.quiet,
        // The rail's own system insets would stack on the ones the region already applies.
        windowInsets   = WindowInsets(0.dp),
    ) {
        // Items sit flush (spacing 0) so the rail is one contiguous column
        // of clickable slots with no dead gap between buttons. Each NavSlot
        // is taller than its icon and centers it, so the breathing room is
        // the slot's own padding -- and stays clickable. No leading spacer
        // for the same reason; fillMaxWidth keeps the slot's Column at rail
        // width so items center.
        // Scrolls when the window is shorter than the rail's content, which a phone held
        // sideways is: the bottom group was cut off with no way to reach it. When it fits,
        // the minimum height keeps the top group up and the bottom group down as before.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("top"), Modifier.fillMaxWidth(), spacing = 0.dp)
                Column(Modifier.fillMaxWidth()) {
                    SlotRenderer(SurfaceId(SIDEBAR_SURFACE), SlotId("bottom"), Modifier.fillMaxWidth(), spacing = 0.dp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private const val SIDEBAR_SURFACE = "appshell.leftrail"
