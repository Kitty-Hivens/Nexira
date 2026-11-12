package hivens.ui

import hivens.config.Storage
import hivens.core.api.interfaces.ISettingsService
import hivens.core.activity.ActivityRegistry
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.PackInstallService
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.platform.PlatformPaths
import hivens.ui.activity.ActivityCommands
import hivens.ui.activity.ActivityDriver
import hivens.ui.activity.SelectionRegistry
import hivens.ui.background.BackgroundManager
import hivens.ui.components.LauncherUpdateState
import hivens.ui.debug.DebugOverlayState
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.feature.catalogue.browse.BrowseController
import hivens.ui.feature.catalogue.browse.BrowseSession
import hivens.ui.feature.catalogue.browse.BrowseTags
import hivens.ui.feature.catalogue.browse.ProjectBrowseSession
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.identity.ClanRoleProvider
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.layout.LayoutGraphFlushHook
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.InstallDriver
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.notifications.drivers.PackUpdateDriver
import hivens.ui.screens.library.content.ContentIconStore
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogRetention
import hivens.ui.utils.PreferenceWriter
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.ui.widgets.modules.WidgetModuleHost
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateFlushHook
import hivens.ui.widgets.state.WidgetStateGc
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.levelServices
import hivens.widget.model.DefaultLayout
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * What each level of the scope tree makes for itself: one per level, closed with it,
 * and what anything injected under that level resolves to.
 */
val levelServicesModule: Module = module {
    // What Browse is searching and where its installs go. Shared by the screen's
    // own widgets and the rail's browse family, which have no composition in
    // common, and kept so a trip to a project page comes back to the same search.
    // One per window, so a second window browses something else.
    levelServices(ScopeLevel.Window) {
        scoped { BrowseController() }
    }
}

/**
 * The Koin singletons the screens and widgets ask for, whatever the host.
 *
 * What differs between hosts comes in as arguments: the registry of widgets compiled into
 * the build (generated per target), how modules are loaded from the widgets directory, and
 * any commands only that host can run. Everything else a host binds on its own.
 */
fun uiCoreModule(
    builtIn: WidgetRegistry,
    moduleHost: (widgetsDir: Path, dataDir: Path) -> WidgetModuleHost,
    platformCommands: Scope.(WidgetCommandRegistry) -> Unit = {},
): Module = module {
    single { SkinManager(get(), get()) }
    single { ClanRoleProvider(get()) }
    single { SkinLibrary(get<Path>().resolve("skins"), get()) }
    single {
        val paths: PlatformPaths = get()
        DefaultSkinProvider(
            librariesDir     = paths.librariesDir,
            cacheDir         = paths.skinCacheDir.resolve("defaults"),
            legacyClientsDir = paths.clientsDir,
        )
    }


    single { GameConsoleService(get()) }
    // The one writer for what the interface saves on a click. createdAtStart, so
    // the drain on exit is armed before the first choice is made.
    single(createdAtStart = true) {
        PreferenceWriter().also { writer ->
            val drainFor = 3.seconds
            Runtime.getRuntime().addShutdownHook(Thread({ writer.drain(drainFor) }, "nexira-preferences-drain"))
        }
    }
    // Holds the per-session game output files to an age and a total size. Once per
    // start, off the boot path; see its KDoc for what it leaves alone.
    single(createdAtStart = true) {
        LogRetention(get<PlatformPaths>().logsDir).also { it.start(get()) }
    }
    // One owner of console.json for the three surfaces that read it: the shell's
    // window, Settings > Console and the pack's Logs tab.
    single { ConsoleSettingsStore(get<Path>(), get(), get()) }
    // Dev UI-debug overlay switchboard. Process-lifetime so the toggle survives
    // shell recomposition + the crash-restart loop; inert on release builds.
    single { DebugOverlayState() }

    // What Browse last showed per source and query, so flipping sources or
    // stepping out of the screen comes back to the list instead of a spinner.
    single { BrowseSession() }
    single { ProjectBrowseSession() }

    includes(levelServicesModule)

    // The catalogue's own lists the filter rail is built from, read once a run.
    single { BrowseTags(get(), get()) }


    // Widget kernel registry. The generated object is one source among however
    // many the build carries: the kernel was always meant to accept widgets from
    // outside a single compilation (the validator's rules apply to a reflected
    // method, the processor warns rather than fails on a contract nobody here
    // provides), and the composite is the part that was never written.
    //
    // Order is precedence and the built-in registry comes first, so a
    // contributed widget cannot take over a kind the application depends on --
    // the shell regions and the sign-in panel are non-removable because a layout
    // without them has no navigation and no way to sign in, and shadowing them
    // by id would be that removal through a side door.
    //
    // The modules can change while the launcher runs: switched off, switched on,
    // the folder read again. The composition reads their state and hears the change;
    // everything outside it asks the registry below, which answers with whatever is
    // current.
    single {
        val dataDir: Path = get()
        val directory = dataDir.resolve(Storage.WIDGETS_DIR)
        WidgetModules(
            directory = directory,
            stateFile = dataDir.resolve("widget-modules.json"),
            json      = get(),
            builtIn   = builtIn,
            scope     = get(),
            host      = moduleHost(directory, dataDir),
        )
    }
    single<WidgetRegistry> { get<WidgetModules>().registry }

    // Cross-widget service registry (Phase D). One global instance per
    // launcher process. Provider widgets register via provideService
    // from inside their composable body (DisposableEffect-driven
    // lifecycle); consumer widgets read via useService<T>() / its
    // siblings. Wired into the composition root from AppShell so
    // LocalWidgetServiceRegistry resolves before any @Widget renders.
    single { WidgetServiceRegistry() }

    // Reactive data sources widgets bind to via rememberSource -- decouples a
    // widget from the concrete service backing its data. Populated eagerly so
    // the registry is complete before the first widget composes.
    single {
        WidgetDataRegistry().apply {
            register(Sources.Activity, flowSource(get<ActivityRegistry>().activities))
            register(Sources.Notifications, flowSource(get<NotificationArchiveStore>().log))
            register(Sources.DoNotDisturb, flowSource(get<NotificationCenter>().doNotDisturb))
            register(Sources.OpenProject, flowSource(get<OpenProjectState>().open))
        }
    }

    // The root of the scope tree. Its app values are the registry above, and the
    // windows, compositions and pages below it are made by their owners. Under the
    // app's coroutine scope, so a level's work stops at shutdown whoever forgot it.
    single {
        WidgetScope.app(registry = get(), services = this, context = get<CoroutineScope>().coroutineContext)
    }

    // Reactive write side: commands widgets fire via rememberCommand /
    // rememberAction without injecting the backing service. Services resolved
    // eagerly, like the data registry above. ClearNotifications is consumed by the
    // notification-history widget. What only some hosts can do is added by the host.
    single {
        val archive: NotificationArchiveStore = get()
        val center: NotificationCenter = get()
        val scope: Scope = this
        WidgetCommandRegistry().apply {
            register(Commands.ClearNotifications, command { archive.clear() })
            register(Commands.SetDoNotDisturb, command { center.setDoNotDisturb(it) })
            scope.platformCommands(this)
        }
    }

    // Widget layout graph persistence. Default graph lives at
    // /widget/default-layout.json inside :widget-model; first run seeds the
    // file, thereafter the on-disk copy is the source of truth. Reactive via
    // StateFlow so the editor mutates the graph live. Lives in the ui module:
    // the layout graph is UI-shell state, the engine has no reader.
    single {
        val dataDir: Path = get()
        LayoutGraphRepository(
            file         = dataDir.resolve(Storage.LAYOUT_GRAPH_FILE),
            json         = get(),
            scope        = get(),
            defaultGraph = { DefaultLayout.load(get()) },
        )
    }

    // Flush pending debounced layout writes on JVM shutdown. createdAtStart so
    // the hook registers during startKoin{}. Runs in parallel with the scope
    // cancellation hook (JVM shutdown hooks run concurrently); flush() is
    // mutex-locked and cancellation-safe, so the race is fine.
    single(createdAtStart = true) { LayoutGraphFlushHook(get()) }

    // Per-instance widget runtime state (rememberWidgetState): the store backs the
    // WidgetStateHost local; the GC collector prunes orphans off the live layout
    // graph; the flush hook lands the debounced write on quit. GC + flush hook are
    // createdAtStart so they wire up before any stateful widget composes / before exit.
    single { WidgetStateStore(get<Path>().resolve("widget-state.json"), get(), get()) }
    single(createdAtStart = true) {
        val presets: PresetRepository = get()
        WidgetStateGc(repo = get(), store = get(), scope = get(), alsoReferenced = presets::referencedInstanceIds)
    }
    single(createdAtStart = true) { WidgetStateFlushHook(get()) }

    // The wallpaper settings, one per process because the manager carries the
    // shutdown flush for its debounced writes.
    single { BackgroundManager(get<Path>(), get()) }

    // What the mod page is looking at, which the right rail's project-view family
    // reads. A singleton because the page that writes it and the rail that reads
    // it are different surfaces with no composition in common.
    single { OpenProjectState() }

    // Which family each surface is showing. A singleton rather than shell state
    // because switching one is something the app DOES -- a navigation, a pack
    // opening, a future plugin -- and not all of that runs inside a composition.
    // Deliberately not persisted: it describes the moment, not the arrangement.
    single { SurfaceFamilies() }

    // Editor mutation facade. Holds no state itself; fires LayoutGraph
    // updates into the shared CoroutineScope so callers stay
    // fire-and-forget.
    single { EditModeController(repo = get(), scope = get()) }


    // Preset storage. One file per preset under <dataDir>/presets/.
    // Atomic write + share-by-file design lets users export to
    // anywhere.
    single {
        val dataDir: Path = get()
        PresetRepository(
            presetsDir = dataDir.resolve(Storage.PRESETS_DIR),
            json       = get(),
        )
    }

    // Notification subsystem: data-only state holders (Center +
    // Registry) are pure singletons; LaunchDriver bridges them to
    // LauncherController for both pack and SC-server flows (the
    // observer is keyed by LaunchTarget so the same singleton handles
    // both kinds of launches without aliasing notifications).
    // Durable message log feeding the notification-history widget. Disk-backed
    // (<dataDir>/notifications.json), survives auto-dismiss + restart.
    single {
        val dataDir: Path = get()
        NotificationArchiveStore(
            file  = dataDir.resolve("notifications.json"),
            json  = get(),
            scope = get(),
        )
    }
    single {
        val settings: ISettingsService = get()
        val preferences: PreferenceWriter = get()
        NotificationCenter(
            archive             = get<NotificationArchiveStore>()::record,
            // Seed the popup-mute from the persisted preference and write the
            // flip back, so "do not disturb" survives a restart.
            initialDoNotDisturb = settings.getSettings().doNotDisturb,
            persistDoNotDisturb = { value ->
                preferences.write("do not disturb") { settings.updateSettings { it.copy(doNotDisturb = value) } }
            },
        )
    }
    single { IndicationCenter() }
    // One account of what the launcher is doing, read by the activity surface.
    // Redaction, rate limiting and the caps are the registry's own contract --
    // see its KDoc for why each is load-bearing on permanent chrome.
    single { ActivityRegistry(scope = get()) }
    // Turns the capabilities the registry advertises back into calls. Kept
    // out of the model on purpose: a lambda field would break Activity's
    // equality and the registry's throttle depends on it.
    single { ActivityCommands(installs = get(), controller = get(), registry = get()) }
    // What the current view has selected. App-scoped so the surface can read it
    // without knowing which screen published it; the view clears it on the way out.
    single { SelectionRegistry() }
    single { ContentIconStore() }
    single { SessionRegistry(appScope = get()) }
    // One-slot handoff: the launch driver parks "needs a code", the shell answers.
    single { hivens.ui.notifications.TwoFactorLaunchGate() }
    single {
        val settingsService: ISettingsService = get()
        LaunchDriver(
            controller      = get(),
            notifications   = get(),
            indications     = get(),
            activities      = get(),
            sessions        = get(),
            gameConsole     = get(),
            appScope        = get(),
            offlineProvider = get(),
            settingsService = settingsService,
            credentialStore = get(),
            twoFactorGate   = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        )
    }
    // Surfaces app-scoped installs (PackInstallService) into the notification
    // center. createdAtStart so its collector is live before the first install
    // can fire; start() launches on the shared app scope.
    single(createdAtStart = true) {
        val settingsService: ISettingsService = get()
        InstallDriver(
            service         = get(),
            notifications   = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        ).also { it.start() }
    }
    // Feeds the self-identifying services (installs, updates, content) into the
    // activity registry. Launch and game entries come from LaunchDriver, which
    // is the only place that knows which pack a LaunchState belongs to.
    // createdAtStart for the same reason as InstallDriver: the collector must
    // be live before the first report can fire.
    single(createdAtStart = true) {
        ActivityDriver(
            registry   = get(),
            installs   = get<PackInstallService>().installs,
            updates    = get<PackUpdateStatusHub>().statuses,
            contentUpdates = get<InstanceContentUpdater>().runs,
            repository = get(),
            appScope   = get(),
        ).also { it.start() }
    }
    // Navigation requests from outside the composition (notification actions,
    // drivers). AppRoot collects and feeds them into the back stack.
    single { NavRequests() }
    single { LauncherUpdateState() }
    // Surfaces pack-update outcomes (background auto-updater + manual flows via
    // the status hub) into the notification center. Same lifecycle rationale as
    // InstallDriver.
    single(createdAtStart = true) {
        val settingsService: ISettingsService = get()
        PackUpdateDriver(
            hub             = get(),
            repository      = get(),
            notifications   = get(),
            nav             = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        ).also { it.start() }
    }
}
