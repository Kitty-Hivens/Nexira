package hivens.ui

import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.RefreshableAuthProvider
import hivens.config.Storage
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.activity.Activity
import hivens.core.activity.ActivityRegistry
import hivens.core.data.SessionData
import hivens.core.data.ThemeMode
import hivens.core.io.AtomicFiles
import hivens.core.launch.LaunchState
import hivens.core.security.SslBypassStore
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.PackInstallService
import hivens.launcher.PackOperationService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.platform.PlatformPaths
import hivens.ui.activity.ActivityCommands
import hivens.ui.activity.ActivityDriver
import hivens.ui.activity.SelectionRegistry
import hivens.ui.appearance.AppearanceStore
import hivens.ui.background.BackgroundManager
import hivens.ui.components.SignOutRequests
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.LauncherUpdateState
import hivens.ui.debug.DebugOverlayState
import hivens.ui.editor.EditModeController
import hivens.ui.editor.WidgetAdmission
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.feature.catalogue.browse.BrowseController
import hivens.ui.feature.catalogue.browse.BrowseSession
import hivens.ui.feature.catalogue.browse.BrowseTags
import hivens.ui.feature.catalogue.browse.CataloguePackPageSource
import hivens.ui.feature.catalogue.browse.ProjectBrowseSession
import hivens.ui.feature.catalogue.browse.sampleCataloguePackPage
import hivens.ui.editor.palette.sampleOpenProject
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.ui.feature.catalogue.project.ProjectPageSource
import hivens.ui.feature.catalogue.project.sampleProjectPage
import hivens.ui.navigation.NavState
import hivens.ui.packs.LauncherPackActions
import hivens.ui.packs.PackActions
import hivens.ui.packs.deletePack
import hivens.ui.packs.launchPack
import hivens.ui.packs.ReattachPreviews
import hivens.ui.packs.previewReattach
import hivens.ui.packs.reattachPack
import hivens.ui.packs.updatePack
import hivens.ui.packs.withdrawNoticesOfInstalledPacks
import hivens.ui.session.SessionStore
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.ThemeManager
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.identity.ClanRoleProvider
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.layout.LayoutGraphFlushHook
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.host.HostWindowState
import hivens.ui.host.WindowPolicy
import hivens.ui.host.WindowRegistry
import hivens.ui.navigation.MainWindowRequests
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CorePart
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.NotifiedCoreProblems
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.PersistedNotification
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
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.Sources
import hivens.ui.widgets.about.AboutUpdateSource
import hivens.ui.widgets.about.OpenUpdateDialog
import hivens.ui.widgets.about.SystemFacts
import hivens.ui.widgets.about.sampleUpdateCheck
import hivens.ui.widgets.levelServices
import hivens.ui.widgets.modules.WidgetModuleHost
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.profile.AccountChanges
import hivens.ui.widgets.profile.ProfileSelection
import hivens.ui.widgets.profile.ProfileSelectionSource
import hivens.ui.widgets.state.WidgetStateFlushHook
import hivens.ui.widgets.state.WidgetStateGc
import hivens.ui.widgets.state.WidgetStateStore
import hivens.ui.widgets.themepicker.ThemeSelectionSource
import hivens.ui.widgets.themepicker.sampleThemeSelection
import hivens.widget.api.FactoryFailures
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.model.DefaultLayout
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * What each level of the scope tree makes for itself: one per level, closed with it,
 * and what anything injected under that level resolves to.
 */
val levelServicesModule: Module = module {
    // One per window each, so a second window has a state of its own and the two
    // stay independent. Everything injected inside a window resolves to its own.
    levelServices(ScopeLevel.Window) {
        // What Browse is searching and where its installs go. Shared by the screen's
        // own widgets and the rail's browse family, which have no composition in
        // common, and kept so a trip to a project page comes back to the same search.
        scoped { BrowseController() }
        // What the mod page is looking at, which the right rail's project-view family
        // reads: the page that writes it and the rail that reads it are different
        // surfaces with no composition in common, both in this window.
        scoped { OpenProjectState() }
        // Which family each surface is showing. Kept out of the composition because
        // a switch is something the window does, a navigation or a pack opening.
        // Deliberately not persisted: it describes the moment, not the arrangement.
        scoped { SurfaceFamilies() }
        // A screen asked for from inside the window, by a card or a link, lands in
        // the window's own history. One from outside every window goes through
        // MainWindowRequests instead.
        scoped { NavRequests() }
        // A sign-out asked for in this window and not yet answered. Held here rather
        // than by the widget that asked, which the answer itself can take away.
        scoped { SignOutRequests() }
    }
}

/**
 * Who the launcher is signed in as: one store for the app, since the accounts are
 * the app's, so no level below can make a second one that signs in again. Made the
 * first time something reads the session, which starts the startup sign-in, and
 * closed with Koin, which stops it.
 *
 * A function rather than one module value: Koin keeps a single's instance in the
 * module it was declared in, so two Koin applications loading one value would share
 * one store, signed in as whichever started first.
 */
fun sessionModule(): Module = module {
    single {
        SessionStore(
            accounts          = get(),
            settings          = get(),
            auth              = get(),
            // Present only when a Microsoft client id is configured, so the startup
            // sign-in refreshes a Microsoft account exactly then.
            refreshable       = get<AuthProviderRegistry>().all.filterIsInstance<RefreshableAuthProvider>().firstOrNull(),
            certificateGrants = get<SslBypassStore>().bypasses,
            problems          = get(),
            parent            = get(),
        )
    } withOptions { onClose { it?.close() } }
}

/**
 * The Koin singletons the screens and widgets ask for, whatever the host.
 *
 * What differs between hosts comes in as arguments: the registry of widgets compiled into
 * the build (generated per target), how modules are loaded from the widgets directory, any
 * commands only that host can run, and how many windows it can show. Everything else a
 * host binds on its own.
 */
fun uiCoreModule(
    builtIn: WidgetRegistry,
    moduleHost: (widgetsDir: Path, dataDir: Path) -> WidgetModuleHost,
    platformCommands: Scope.(WidgetCommandRegistry) -> Unit = {},
    windowPolicy: WindowPolicy = WindowPolicy.Unlimited,
): Module = module {
    // The windows the launcher has open. One for the process: it outlives a shell
    // restarted after a crash, and each window leaves it as its shell goes.
    single { WindowRegistry(windowPolicy) }
    // Notification actions and drivers navigate here, into the main window.
    single { MainWindowRequests() }

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

    includes(levelServicesModule, sessionModule())

    // What the core could not do, said in the notification centre in the person's language.
    // A pack said to be missing is taken back once a pack of that id is installed.
    single<CoreProblems> {
        val settingsService: ISettingsService = get()
        NotifiedCoreProblems(notifications = get(), scope = get()) {
            stringsFor(AppLocale.fromTag(settingsService.getSettings().locale))
        }.also { problems -> withdrawNoticesOfInstalledPacks(get<IPackRepository>().observe(), problems, get()) }
    }

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

    // Who signed in or out since the start, for every list of accounts to hear it.
    single { AccountChanges() }

    // How the launcher looks and speaks, for every window and composition alike.
    // The module themes are read off the loaded modules as they change.
    single {
        val appScope: CoroutineScope = get()
        val modules: WidgetModules = get()
        AppearanceStore(
            settings    = get(),
            preferences = get(),
            themes      = ThemeManager(get<Path>(), AtomicFiles::writeString),
            contributed = modules.state.map { it.themes }.stateIn(appScope, SharingStarted.Eagerly, modules.state.value.themes),
            scope       = appScope,
        )
    }

    // What the pack commands do, over the launcher's own services.
    single<PackActions> {
        LauncherPackActions(
            controller     = get(),
            driver         = get(),
            installService = get(),
            operations     = get(),
            updater        = get(),
            hub            = get(),
            instances      = get(),
        )
    }

    // The reattach previews, one per pack that asked, for the screen and any widget
    // alike. A reattach runs only on one of them.
    single {
        val instances: PackInstanceService = get()
        val packs: IPackRepository = get()
        ReattachPreviews(
            // The record as it is when measured, not as it was asked about: a
            // preview measured again after work on the pack reads what that work left.
            measure    = { pack -> instances.previewReattach(packs.get(pack.id) ?: pack) },
            operations = get<PackOperationService>().operations,
            scope      = get(),
            discard    = instances::discardReattachPreview,
        )
    }

    // Reactive data sources widgets bind to via rememberSource -- decouples a
    // widget from the concrete service backing its data. Populated eagerly so
    // the registry is complete before the first widget composes.
    single {
        val appScope: CoroutineScope = get()
        uiDataRegistry(
            activity         = get<ActivityRegistry>().activities,
            notifications    = get<NotificationArchiveStore>().log,
            doNotDisturb     = get<NotificationCenter>().doNotDisturb,
            sslBypass        = sslBypassSource(get(), get<ServerProtocolConfig>().sslBypassHost, appScope),
            background       = get<BackgroundManager>().settings,
            accountsRevision = get<AccountChanges>().revision,
        )
    }

    // The root of the scope tree. Its app values are the registries here, and the
    // windows, compositions and pages below it are made by their owners. Under the
    // app's coroutine scope, so a level's work stops at shutdown whoever forgot it.
    // A factory that cannot make its key is said out loud: a core service that did
    // not start leaves Play doing nothing and the library empty, which nobody could
    // tell from a launcher that has no packs.
    single {
        WidgetScope.app(
            registry = get(),
            services = KoinLevelServicesHost.app(this),
            context  = get<CoroutineScope>().coroutineContext,
            commands = get(),
            failures = reportedTo(get()),
        )
    }

    // Reactive write side: commands widgets fire via rememberCommand /
    // rememberAction without injecting the backing service. Services resolved
    // eagerly, like the data registry above. ClearNotifications is consumed by the
    // notification-history widget. What only some hosts can do is added by the host.
    single {
        val archive: NotificationArchiveStore = get()
        val center: NotificationCenter = get()
        val backgrounds: BackgroundManager = get()
        val accounts: AccountChanges = get()
        val scope: Scope = this
        uiCommandRegistry(
            clearNotifications = { archive.clear() },
            setDoNotDisturb    = { center.setDoNotDisturb(it) },
            updateBackground   = { change -> backgrounds.update(change) },
            accountsChanged    = { accounts.changed() },
        ).apply { scope.platformCommands(this) }
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

    // Editor mutation facade. Holds no state itself; fires LayoutGraph
    // updates into the shared CoroutineScope so callers stay
    // fire-and-forget. It refuses a widget where what it reads has no answer,
    // asking the scope tree's registries where each key lives.
    single {
        val appLevel: WidgetScope = get()
        val widgets: WidgetRegistry = get()
        EditModeController(
            repo      = get(),
            scope     = get(),
            admission = WidgetAdmission(appLevel::declarationOf) { kind -> widgets[kind]?.needs.orEmpty() },
        )
    }


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

/** Every factory of the scope tree that could not make its key, told to [problems] as a part that is missing. */
internal fun reportedTo(problems: CoreProblems): FactoryFailures =
    FactoryFailures { id, _, cause -> problems.report(CoreProblem.PartMissing(corePartOf(id), cause)) }

/**
 * The part of the core the key [id] stands for, which is what a person is told is
 * missing when the key cannot be made. The id itself goes to the log only.
 */
internal fun corePartOf(id: String): CorePart = CORE_PARTS[id] ?: CorePart.Other

private val CORE_PARTS: Map<String, CorePart> = buildMap {
    listOf(Sources.Session.id, Commands.SignIn.id, Commands.SignOut.id).forEach { put(it, CorePart.Session) }
    listOf(
        Sources.ThemeLibrary.id, Sources.DarkTheme.id, Sources.ThemeMode.id, Sources.SystemSchemeAvailable.id, Sources.Locale.id,
        Commands.ApplyTheme.id, Commands.SetDarkTheme.id, Commands.SetThemeMode.id, Commands.SetLocale.id,
    ).forEach { put(it, CorePart.Appearance) }
    listOf(
        Sources.PackLibrary.id, Sources.PackLaunches.id, Sources.LaunchState.id, Sources.PackInstalls.id,
        Commands.LaunchPack.id, Commands.StopPack.id, Commands.InstallPack.id, Commands.DismissInstall.id,
        Commands.CancelInstall.id, Commands.UpdatePack.id, Commands.DeletePack.id, Commands.ReattachPack.id,
        Commands.PreviewReattach.id, Sources.ReattachPreviews.id,
    ).forEach { put(it, CorePart.Packs) }
    listOf(Sources.ConsoleVisible.id, Commands.SetConsoleVisible.id).forEach { put(it, CorePart.Console) }
}

/**
 * The app's data registry as the launcher builds it, from the flows the services
 * behind each source hold. Apart from the module so a test builds the very same
 * registry from stand-in flows and checks every widget's needs against it.
 */
internal fun uiDataRegistry(
    activity: StateFlow<List<Activity>>,
    notifications: StateFlow<List<PersistedNotification>>,
    doNotDisturb: StateFlow<Boolean>,
    sslBypass: WidgetDataSource<Boolean>,
    background: StateFlow<BackgroundSettings>,
    accountsRevision: StateFlow<Int>,
): WidgetDataRegistry = WidgetDataRegistry().apply {
    register(Sources.Activity, flowSource(activity))
    register(Sources.Notifications, flowSource(notifications))
    register(Sources.DoNotDisturb, flowSource(doNotDisturb))
    // Read off the window's own state, the one its pages publish into.
    registerFactory(Sources.OpenProject, sample = ::sampleOpenProject) { window ->
        flowSource(window.services.get<OpenProjectState>().open)
    }
    // What the window's owner provides when it opens the window: its state as the host reads it.
    declare(Sources.Window) { HostWindowState.Sample }
    register(Sources.SslBypass, sslBypass)
    register(Sources.Background, flowSource(background))
    register(Sources.AccountsRevision, flowSource(accountsRevision))
    // Read the first time a card asks rather than at startup: asking the OS is slow,
    // and most runs never open the page that shows it.
    registerFactory(Sources.SystemInfo) { flowSource(MutableStateFlow(SystemFacts.read())) }
    // One per page, so a profile page kept in the history comes back on the section
    // it was left on.
    registerFactory(ProfileSelectionSource, sample = ::ProfileSelection) { flowSource(MutableStateFlow(ProfileSelection())) }
    // The launcher's session, from the app's one store. Every key here carries the
    // sample the palette and the editor show a widget on where no level of its kind
    // is above: display data, never handed to a widget drawn for use.
    registerFactory(Sources.Session, sample = { AppState.Authenticated(SessionData(playerName = "Steve")) }) { app ->
        flowSource(app.services.get<SessionStore>().state)
    }
    // How the composition looks and speaks, read off the app's one appearance store.
    registerFactory(Sources.ThemeLibrary, sample = ::ThemeLibrary) { composition ->
        flowSource(composition.services.get<AppearanceStore>().library)
    }
    registerFactory(Sources.DarkTheme, sample = { true }) { composition ->
        flowSource(composition.services.get<AppearanceStore>().dark)
    }
    registerFactory(Sources.ThemeMode, sample = { ThemeMode.Manual }) { composition ->
        flowSource(composition.services.get<AppearanceStore>().mode)
    }
    registerFactory(Sources.SystemSchemeAvailable, sample = { false }) { app ->
        flowSource(app.services.get<AppearanceStore>().systemSchemeAvailable)
    }
    registerFactory(Sources.Locale, sample = { AppLocale.ENGLISH }) { composition ->
        flowSource(composition.services.get<AppearanceStore>().locale)
    }
    // The packs and what is being done to them, for the whole app.
    registerFactory(Sources.PackLibrary, sample = { emptyList() }) { app ->
        flowSource(app.services.get<IPackRepository>().observe())
    }
    registerFactory(Sources.PackLaunches, sample = { emptyMap() }) { app ->
        flowSource(app.services.get<IndicationCenter>().launches)
    }
    registerFactory(Sources.LaunchState, sample = { LaunchState.Idle }) { app ->
        flowSource(app.services.get<PackActions>().launchState)
    }
    registerFactory(Sources.PackInstalls, sample = { emptyMap() }) { app ->
        flowSource(app.services.get<PackActions>().installs)
    }
    registerFactory(Sources.ReattachPreviews, sample = { emptyMap() }) { app ->
        flowSource(app.services.get<ReattachPreviews>().previews)
    }
    registerFactory(Sources.ConsoleVisible, sample = { false }) { app ->
        flowSource(app.services.get<GameConsoleService>().visible)
    }
    // What the composition's owner provides around every surface it draws, with the
    // sample in the sense above.
    declare(Sources.Navigation) { NavState(Screen.Home, listOf(Screen.Home), canGoBack = false, canGoForward = false) }
    // What a page provides for its own widgets, on that page and nowhere else.
    declare(ProjectPageSource, ::sampleProjectPage)
    declare(CataloguePackPageSource, ::sampleCataloguePackPage)
    declare(ThemeSelectionSource, ::sampleThemeSelection)
    declare(AboutUpdateSource, ::sampleUpdateCheck)
}

/**
 * The app's command registry as the launcher builds it, before a host adds what
 * only it can run. Apart from the module for the reason [uiDataRegistry] is.
 */
internal fun uiCommandRegistry(
    clearNotifications: () -> Unit,
    setDoNotDisturb: (Boolean) -> Unit,
    updateBackground: (BackgroundSettings.() -> BackgroundSettings) -> Unit,
    accountsChanged: () -> Unit,
): WidgetCommandRegistry = WidgetCommandRegistry().apply {
    register(Commands.ClearNotifications, command { clearNotifications() })
    register(Commands.SetDoNotDisturb, command(setDoNotDisturb))
    register(Commands.UpdateBackground, command(updateBackground))
    register(Commands.AccountsChanged, command { accountsChanged() })
    // The launcher's session, through the app's one store.
    registerFactory(Commands.SignIn) { app -> command(app.services.get<SessionStore>()::signIn) }
    registerFactory(Commands.SignOut) { app ->
        val store = app.services.get<SessionStore>()
        command { val _ = store.signOut() }
    }
    // How the composition looks and speaks, through the app's appearance store.
    registerFactory(Commands.ApplyTheme) { composition -> command(composition.services.get<AppearanceStore>()::select) }
    registerFactory(Commands.SetDarkTheme) { composition -> command(composition.services.get<AppearanceStore>()::setDark) }
    registerFactory(Commands.SetThemeMode) { composition -> command(composition.services.get<AppearanceStore>()::setMode) }
    registerFactory(Commands.SetLocale) { composition -> command(composition.services.get<AppearanceStore>()::setLocale) }
    // What is done to packs is the same whoever asks. A launch runs as the launcher's session.
    registerFactory(Commands.LaunchPack) { app ->
        val services = app.services
        val store = services.get<SessionStore>()
        val packs = services.get<IPackRepository>()
        val actions = services.get<PackActions>()
        val settings = services.get<ISettingsService>()
        val offline = services.get<OfflineAuthProvider>()
        val problems = services.get<CoreProblems>()
        command { id -> launchPack(id, store.state.value, packs, actions, settings, offline, problems, app.coroutineScope) }
    }
    registerFactory(Commands.StopPack) { app -> command(app.services.get<PackActions>()::stop) }
    registerFactory(Commands.InstallPack) { app -> command(app.services.get<PackActions>()::install) }
    registerFactory(Commands.DismissInstall) { app -> command(app.services.get<PackActions>()::dismissInstall) }
    registerFactory(Commands.CancelInstall) { app -> command(app.services.get<PackActions>()::cancelInstall) }
    registerFactory(Commands.UpdatePack) { app ->
        val packs = app.services.get<IPackRepository>()
        val actions = app.services.get<PackActions>()
        command { id -> updatePack(id, packs, actions) }
    }
    registerFactory(Commands.DeletePack) { app ->
        val packs = app.services.get<IPackRepository>()
        val actions = app.services.get<PackActions>()
        command { id -> deletePack(id, packs, actions, app.coroutineScope) }
    }
    registerFactory(Commands.PreviewReattach) { app ->
        val packs = app.services.get<IPackRepository>()
        val previews = app.services.get<ReattachPreviews>()
        command { id -> previewReattach(id, packs, previews) }
    }
    registerFactory(Commands.ReattachPack) { app ->
        val packs = app.services.get<IPackRepository>()
        val actions = app.services.get<PackActions>()
        command { request -> reattachPack(request, packs, actions) }
    }
    registerFactory(Commands.SetConsoleVisible) { app ->
        val console = app.services.get<GameConsoleService>()
        command { visible -> if (visible) console.show() else console.hide() }
    }
    // What the composition's owner provides: its history.
    declare(Commands.Navigate)
    declare(Commands.SwitchTab)
    declare(Commands.Back)
    declare(Commands.Forward)
    declare(Commands.PopTo)
    // What the About page provides for its update panel.
    declare(OpenUpdateDialog)
    // What each window provides of the commands it takes.
    declare(Commands.MaximizeWindow)
    declare(Commands.MinimizeWindow)
    declare(Commands.RestoreWindow)
    declare(Commands.CloseWindow)
}

/**
 * Whether the launcher's own server is reached past its certificate. The store
 * keeps every grant and answers per host, so its grants are read through the one
 * host the protocol names, and a grant expiring is heard on the next change to them,
 * the way the rail heard it before it read this.
 */
private fun sslBypassSource(store: SslBypassStore, host: String, scope: CoroutineScope): WidgetDataSource<Boolean> =
    flowSource(store.bypasses.map { store.isBypassed(host) }.stateIn(scope, SharingStarted.Eagerly, store.isBypassed(host)))
