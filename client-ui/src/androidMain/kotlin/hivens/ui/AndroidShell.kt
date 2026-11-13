package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import hivens.ui.chrome.ShellChord
import hivens.ui.chrome.resolveShellChord
import hivens.ui.editor.EditModeController
import androidx.activity.compose.BackHandler
import hivens.auth.AccountStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.ReadOnlyStore
import hivens.core.data.ThemeMode
import hivens.core.data.darkThemeFor
import hivens.core.data.resolveInitialThemeMode
import hivens.core.diag.ActionRing
import hivens.core.io.AtomicFiles
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.data.NewerBuildData
import hivens.launcher.launch.LauncherController
import hivens.launcher.update.ApplyRecovery
import hivens.launcher.update.PackAutoUpdateService
import hivens.ui.bootstrap.ShellStartup
import hivens.ui.bootstrap.StartupPolicy
import hivens.ui.components.CertificatePromptHost
import hivens.ui.components.TwoFactorPromptHost
import hivens.ui.console.ConsoleCommands
import hivens.ui.customization.CustomizationManager
import hivens.ui.customization.LocalCustomization
import hivens.ui.debug.IdentitySlotChromeModifier
import hivens.ui.debug.IdentityWidgetDecorator
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.editor.WidgetGraphReconciler
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.i18n.LocalStrings
import hivens.ui.layers.DismissOnBack
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.logic.LaunchLogCollector
import hivens.ui.screens.ConsoleContent
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.text.needsCjkFace
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxTheme
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.ThemeManager
import hivens.ui.theme.ThemeRevealHost
import hivens.ui.theme.nexiraCjkFamily
import hivens.ui.theme.rememberThemeReveal
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.PreferenceWriter
import hivens.ui.chrome.LocalRightRailSheet
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.NxMapControls
import hivens.ui.widgets.NxViewportScrollbar
import hivens.ui.widgets.PlayedWidgetEntrance
import hivens.ui.widgets.WidgetSurface
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMapControls
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalSlotChromeModifier
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalViewportScrollbar
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.LocalWidgetEntrance
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetSurfaceRenderer
import hivens.widget.model.DefaultLayout
import hivens.widget.model.ScopeLevel
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The launcher on a phone: the same [AppRoot] and the same widget graph the desktop shell
 * hosts, around the pieces a window does not apply to.
 *
 * What it leaves to the desktop: the tray and the system notifier, hiding the window while a
 * game runs, the custom window chrome, the wallpaper pipeline, the console as a window of its
 * own (it is an overlay here) and the quit question (the system owns the lifecycle).
 */
@Composable
fun AndroidShell() {
    val settingsService: ISettingsService = koinInject()
    val controller: LauncherController = koinInject()
    val gameConsole: GameConsoleService = koinInject()
    val layoutGraphRepo: LayoutGraphRepository = koinInject()
    val widgetModules: WidgetModules = koinInject()
    val widgetServiceRegistry: WidgetServiceRegistry = koinInject()
    val widgetDataRegistry: WidgetDataRegistry = koinInject()
    val appLevel: WidgetScope = koinInject()
    val widgetCommandRegistry: WidgetCommandRegistry = koinInject()
    val widgetStateStore: WidgetStateStore = koinInject()
    val surfaceFamilies: SurfaceFamilies = koinInject()
    val applicationScope: CoroutineScope = koinInject()
    val preferences: PreferenceWriter = koinInject()
    val accountStore: AccountStore = koinInject()
    val dataDirectory: Path = koinInject()
    val updateRecovery: ApplyRecovery = koinInject()
    val packAutoUpdates: PackAutoUpdateService = koinInject()
    val editModeController: EditModeController = koinInject()

    val settings = remember { settingsService.getSettings() }

    LaunchedEffect(Unit) {
        ConsoleCommands.registerAll(
            console = gameConsole,
            scope = applicationScope,
            debugOverlayToggle = null,
            launcherIsBusy = {
                val state = controller.state.value
                state !is LaunchState.Idle && state !is LaunchState.Error
            },
            restartWorld = { false },
        )
    }

    var isDarkTheme by remember { mutableStateOf(settings.isDarkTheme) }
    var themeMode by remember { mutableStateOf(resolveInitialThemeMode(settings)) }
    var currentLocale by remember { mutableStateOf(AppLocale.fromTag(settings.locale)) }

    // Both automatic sources write through isDarkTheme, so everything downstream reads one
    // boolean. The wallpaper source needs a wallpaper, which a phone does not draw.
    val systemDark = isSystemInDarkTheme()
    val applyAutomaticDark: (Boolean?) -> Unit = { wanted ->
        if (wanted != null) {
            isDarkTheme = wanted
            preferences.write("the colour scheme") { settingsService.updateSettings { it.copy(isDarkTheme = wanted) } }
        }
    }
    var wallpaperLuminance by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(themeMode, systemDark, wallpaperLuminance) {
        applyAutomaticDark(darkThemeFor(themeMode, isDarkTheme, systemDark = systemDark, wallpaperLuminance = wallpaperLuminance))
    }

    LaunchedEffect(Unit) {
        val before = layoutGraphRepo.value()
        val defaultKinds = DefaultLayout.load().walkInstances().map { it.kind }.toSet() + widgetModules.knownKinds()
        val result = WidgetGraphReconciler.reconcile(
            graph = before,
            registry = widgetModules.registry,
            defaultKinds = defaultKinds,
            prune = layoutGraphRepo.migratedFromSchema != null,
        )
        if (result.graph != before) {
            LoggerFactory.getLogger("AndroidShell").info(
                "Layout reconcile: seeded {} slot(s), pruned {} widget(s)", result.seededSlots, result.prunedWidgets,
            )
            layoutGraphRepo.update { result.graph }
        }
    }

    LaunchLogCollector(events = controller.events, gameConsole = gameConsole)

    // The flag is what stops later launches from logging in again, and a login invalidates the
    // session the user unlocked with a code.
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            if (event !is LaunchLogEvent.TwoFactorDetected) return@collect
            runCatching {
                withContext(Dispatchers.IO) { accountStore.markTwoFactor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY) }
            }.onSuccess { ActionRing.record("Launch met the second factor: the SmartyCraft account is marked") }
        }
    }

    LaunchedEffect(Unit) {
        ShellStartup(
            policy = StartupPolicy(trayEnabled = false, notifierEnabled = false, autoUpdatePacks = settings.autoUpdatePacks),
            bringUpTray = {},
            bringUpNotifier = {},
            readIcon = { ByteArray(0) },
            trayIsSupported = { true },
            showWindow = {},
            recoverInterrupted = { updateRecovery.recoverInterrupted() },
            autoUpdatePacks = { packAutoUpdates.runOnce() },
            appScope = applicationScope,
        ).run(windowVisible = { true })
    }

    CompositionLocalProvider(LocalAprilFools provides AprilFoolsLoader.instance) {
        LocaleProvider(locale = currentLocale) {
            val s = LocalStrings.current
            val uiFamily = if (needsCjkFace(s.settingsTitle + s.navLibrary + s.aboutTitle)) nexiraCjkFamily() else null

            val themeManager = remember { ThemeManager(dataDirectory, AtomicFiles::writeString) }
            var themeLibrary by remember {
                val loaded = themeManager.load()
                if (themeManager.readOnly) NewerBuildData.record(ReadOnlyStore.Theme)
                mutableStateOf(loaded)
            }
            val customizationJson = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true } }
            val customizationManager = remember { CustomizationManager(dataDirectory, customizationJson, AtomicFiles::writeString) }
            var customization by remember { mutableStateOf(customizationManager.load()) }

            val layoutGraph by remember(layoutGraphRepo) { layoutGraphRepo.observe() }.collectAsState()
            val modules by widgetModules.state.collectAsState()
            val surfaceRenderer: WidgetSurfaceRenderer = remember { { spec, content -> WidgetSurface(spec, content) } }

            CompositionLocalProvider(
                LocalCustomization provides customization,
                LocalLayoutGraph provides layoutGraph,
                LocalWidgetRegistry provides modules.registry,
                LocalWidgetServiceRegistry provides widgetServiceRegistry,
                LocalWidgetDataRegistry provides widgetDataRegistry,
                LocalScope provides appLevel,
                LocalWidgetCommandRegistry provides widgetCommandRegistry,
                LocalWidgetStateHost provides widgetStateStore,
                LocalSurfaceFamilies provides surfaceFamilies,
                LocalWidgetDecorator provides IdentityWidgetDecorator,
                LocalSlotChromeModifier provides IdentitySlotChromeModifier,
                LocalWidgetSurfaceRenderer provides surfaceRenderer,
                LocalWidgetEntrance provides PlayedWidgetEntrance,
                LocalViewportScrollbar provides NxViewportScrollbar,
                LocalMapControls provides NxMapControls,
                LocalUseCustomChrome provides false,
                LocalRightRailSheet provides true,
            ) {
              // The one window a phone has, for as long as this shell draws it.
              ChildLevel(ScopeLevel.Window) {
                // The window's layers and its dismiss stack, at the root of its level and around
                // the theme, as on the desktop: the host belongs to the window, and a layer takes
                // its theme from where it is opened.
                val layers = rememberWindowLayers()
                val focus = remember { FocusRequester() }
                val refocus = rememberCoroutineScope()
                LaunchedEffect(Unit) { focus.requestFocus() }
                // Around the layers rather than inside the window's own content, so a key
                // from a menu or a dialog that holds the focus passes these hooks as well.
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        // The shell's keys are read here, so they stop when nothing in the
                        // shell holds the focus, as after Escape takes it from a field.
                        .onFocusChanged { if (!it.hasFocus) refocus.launch { focus.requestFocus() } }
                        .focusable()
                        .onPreviewKeyEvent { ev ->
                            layers.dismiss.onPreviewKeyEvent(ev)
                            val resolved = resolveShellChord(ev, false, editModeController.isEditing)
                            when (resolved.chord) {
                                ShellChord.ToggleEditMode -> editModeController.requestEditToggle()
                                ShellChord.ToggleRightRail -> editModeController.requestRightRailToggle()
                                ShellChord.UndoEdit -> editModeController.undo()
                                ShellChord.RedoEdit -> editModeController.redo()
                                ShellChord.ToggleDebugOverlay, null -> Unit
                            }
                            resolved.consume
                        }
                        // A hardware Escape, after whatever holds the focus has had it and
                        // before the system turns it into back: a held key's repeats stop here.
                        .onKeyEvent(layers.dismiss::onKeyEvent),
                ) {
                LayerHost(layers, Modifier.fillMaxSize()) {
                val themeReveal = rememberThemeReveal()
                NxTheme(theme = themeLibrary.active, dark = isDarkTheme, uiFamily = uiFamily) {
                    ThemeRevealHost(themeReveal) {
                        // The system bars and the caption of a freeform window are the system's, so the
                        // layout yields room to them instead of drawing under.
                        // Drawn edge to edge: each region paints under the system bars and pads its own
                        // content by them (see the shell regions), so nothing is padded out here.
                        Box(Modifier.fillMaxSize().background(NxColor.page)) {
                            AppRoot(
                                onWallpaperLuminance = { wallpaperLuminance = it },
                                onWallpaperColours = { colours ->
                                    // Stored with the selection, so the wallpaper theme opens in
                                    // itself next time. Written only when the picture changed.
                                    if (colours != themeLibrary.wallpaper) {
                                        val library = themeLibrary.copy(wallpaper = colours)
                                        themeLibrary = library
                                        preferences.write("the wallpaper colours") { themeManager.save(library) }
                                    }
                                },
                                onRealExit = {},
                                onHideToTray = null,
                                isDarkTheme = isDarkTheme,
                                onToggleDarkTheme = {
                                    isDarkTheme = !isDarkTheme
                                    themeMode = ThemeMode.Manual
                                    val dark = isDarkTheme
                                    preferences.write("the colour scheme") {
                                        settingsService.updateSettings { it.copy(isDarkTheme = dark, themeMode = ThemeMode.Manual, themeFromWallpaper = false) }
                                    }
                                },
                                themeMode = themeMode,
                                onThemeModeChanged = { mode ->
                                    themeMode = mode
                                    preferences.write("the theme mode") {
                                        settingsService.updateSettings { it.copy(themeMode = mode, themeFromWallpaper = mode == ThemeMode.Wallpaper) }
                                    }
                                },
                                systemThemeAvailable = true,
                                themeLibrary = themeLibrary,
                                onThemeSelected = { id ->
                                    val library = themeLibrary.copy(selected = id)
                                    themeLibrary = library
                                    preferences.write("the theme") { themeManager.save(library) }
                                },
                                currentLocale = currentLocale,
                                onLocaleChanged = { locale ->
                                    currentLocale = locale
                                    preferences.write("the language") { settingsService.updateSettings { it.copy(locale = locale.tag) } }
                                },
                                customization = customization,
                                onCustomizationChanged = { updated ->
                                    customization = updated
                                    preferences.write("the customization") { customizationManager.save(updated) }
                                },
                            )

                            // The one Back the window's dismiss stack has, closing what Escape would.
                            // After AppRoot, so it is asked before the navigation's own Back, which
                            // gets the gesture only when nothing is open.
                            BackHandler(enabled = layers.dismiss.handlesBack) { layers.dismiss.dismissTop() }

                            if (gameConsole.shouldShowConsole) {
                                DismissOnBack { gameConsole.hide() }
                                val settingsStore: ConsoleSettingsStore = koinInject()
                                val consoleSettings by settingsStore.settings.collectAsState()
                                NxSurface(SurfaceKind.Page, Modifier.fillMaxSize()) {
                                    // Not safeDrawing: a phone on its side reports its camera as a band the height of the
                                    // screen, and the gesture bar needs nothing kept clear under it.
                                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.tappableElement).union(WindowInsets.ime))) {
                                        ConsoleContent(settings = consoleSettings, onSettingsChange = settingsStore::update)
                                    }
                                }
                            }
                        }
                        TwoFactorPromptHost()
                        CertificatePromptHost()
                    }
                }
                } // end LayerHost
                } // end key hooks
              }
            }
        }
    }
}
