package hivens.ui

import hivens.accounts.AccountsHost
import hivens.accounts.TwoFactorLaunchGate
import hivens.config.Storage
import hivens.core.activity.Activity
import hivens.core.activity.ActivityRegistry
import hivens.core.io.readUtf8
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.ReadingLanguage
import hivens.core.security.SslBypassStore
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.PackInstallService
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.sources.SourceWorks
import hivens.packs.PacksHost
import hivens.ui.accounts.ClientAccountsHost
import hivens.ui.activity.ActivityCommands
import hivens.ui.activity.ActivityDriver
import hivens.ui.activity.SelectionRegistry
import hivens.ui.background.BackgroundManager
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.LauncherUpdateState
import hivens.ui.components.SignOutRequests
import hivens.ui.debug.DebugOverlayState
import hivens.ui.editor.DocumentAdmission
import hivens.ui.editor.DocumentEditor
import hivens.ui.editor.ShellSignals
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.feature.catalogue.browse.CataloguePackPageSource
import hivens.ui.feature.catalogue.browse.sampleCataloguePackPage
import hivens.ui.feature.catalogue.project.ProjectPageSource
import hivens.ui.feature.catalogue.project.sampleProjectPage
import hivens.ui.host.CompositionWindows
import hivens.ui.host.HostWindowState
import hivens.ui.host.HostWords
import hivens.ui.host.LauncherFiles
import hivens.ui.host.LauncherHostAccess
import hivens.ui.host.WindowPolicy
import hivens.ui.host.WindowRegistry
import hivens.ui.host.sourceWorksOf
import hivens.ui.i18n.NexiraStrings
import hivens.ui.identity.ClanRoleProvider
import hivens.ui.identity.DefaultSkinProvider
import hivens.ui.identity.SkinLibrary
import hivens.ui.identity.SkinManager
import hivens.ui.layout.DocumentStore
import hivens.ui.navigation.CompositionHistories
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.MainWindowRequests
import hivens.ui.navigation.NavRequests
import hivens.ui.navigation.PackSettingsPage
import hivens.ui.notifications.CorePart
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.NotificationArchiveStore
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.NotifiedCoreProblems
import hivens.ui.notifications.PersistedNotification
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.InstallDriver
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.notifications.drivers.PackUpdateDriver
import hivens.ui.packs.ClientPacksHost
import hivens.ui.packs.withdrawNoticesOfInstalledPacks
import hivens.ui.screens.library.content.ContentIconStore
import hivens.ui.settings.LegacySettingsThroughService
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogRetention
import hivens.ui.utils.PreferenceWriter
import hivens.ui.widgets.Commands
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.Sources
import hivens.ui.widgets.about.AboutUpdateSource
import hivens.ui.widgets.about.OpenUpdateDialog
import hivens.ui.widgets.about.sampleUpdateCheck
import hivens.ui.widgets.about.systemFactsReadOff
import hivens.ui.widgets.levelServices
import hivens.ui.widgets.modules.CompiledModules
import hivens.ui.widgets.modules.WidgetModuleHost
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.modules.broughtIn
import hivens.ui.widgets.modules.hostTakenContributions
import hivens.ui.widgets.modules.hostTakenWords
import hivens.ui.widgets.modules.moduleGraph
import hivens.ui.widgets.profile.ProfileSelection
import hivens.ui.widgets.profile.ProfileSelectionSource
import hivens.ui.widgets.state.WidgetStateFlushHook
import hivens.ui.widgets.state.WidgetStateGc
import hivens.ui.widgets.state.WidgetStateStore
import hivens.ui.widgets.state.instanceIdsIn
import hivens.widget.api.BringIn
import hivens.widget.api.CompiledModule
import hivens.widget.api.CompositionKeys
import hivens.widget.api.ContributedServices
import hivens.widget.api.FactoryFailures
import hivens.widget.api.HostFiles
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleKeysResult
import hivens.pages.api.PageKeys
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContributions
import hivens.widget.api.TakenWords
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WindowKeys
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostServices
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.Document
import hivens.history.api.History
import hivens.pages.api.HistoryEntry
import hivens.widget.model.ScopeLevel
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import hivens.history.api.HistoryKeys
import hivens.pages.api.PagesKeys
import hivens.pages.api.read

/**
 * What each level of the scope tree makes for itself: one per level, closed with it,
 * and what anything injected under that level resolves to.
 */
val levelServicesModule: Module = module {
    // One per window each, so a second window has a state of its own and the two
    // stay independent. Everything injected inside a window resolves to its own.
    levelServices(ScopeLevel.Window) {
        // What Browse searches and the project the rail describes are the catalogue
        // module's, one of each per window, made by the window's level from its keys.
        //
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
 * The Koin singletons the screens and widgets ask for, whatever the host.
 *
 * What differs between hosts comes in as arguments: the registry of widgets compiled into
 * the build (generated per target), how modules are loaded from the widgets directory, any
 * commands only that host can run, and how many windows it can show. Everything else a
 * host binds on its own.
 *
 * [compiled] is every module compiled into the build. Which of them, and which of the
 * jars in the widgets directory, bring their keys in is the document's decision: see
 * [moduleKeys].
 */
fun uiCoreModule(
    builtIn: WidgetRegistry,
    moduleHost: (widgetsDir: Path, dataDir: Path) -> WidgetModuleHost,
    platformCommands: Scope.(WidgetCommandRegistry) -> Unit = {},
    windowPolicy: WindowPolicy = WindowPolicy.Unlimited,
    compiled: List<CompiledModule> = CompiledModules,
): Module = module {
    // The windows the launcher has open. One for the process: it outlives a shell
    // restarted after a crash, and each window leaves it as its shell goes.
    single { WindowRegistry(windowPolicy) }
    // Where each composition stands, for an interface that starts over to go on from.
    single { CompositionHistories() }
    // The document the windows are opened from, and the compositions shown in a window
    // of their own. Kept for the process, so a window stays open while the interface
    // starts over.
    single<Document> { StandardDocument.load() }
    single {
        val preferences: PreferenceWriter = get()
        DocumentStore(file = get<Path>().resolve(Storage.INTERFACE_FILE), standard = { get<Document>() }, save = preferences::write)
    }
    single {
        val document: Document = get()
        // The composition the launcher starts in has its window already.
        CompositionWindows { it in document.compositions && it != document.start }
    }
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

    includes(levelServicesModule)

    // The modules' settings, one file per settings domain, beside the settings file they were
    // split out of, which a domain reads until it has a file and mirrors its old fields into for
    // an older build. That file is written through the settings service, which holds a copy of
    // it. Saved by the writer the interface's other choices go through, off the event thread and
    // in order.
    single {
        val dataDir: Path = get()
        val preferences: PreferenceWriter = get()
        SettingsDomainFiles(
            dir    = dataDir.resolve(Storage.SETTINGS_DIR),
            legacy = LegacySettingsThroughService(dataDir.resolve(Storage.SETTINGS_FILE), get(), get()),
            write  = preferences::write,
        )
    }
    single<SettingsStores> { get<SettingsDomainFiles>() }

    // The launcher's data directory as the modules compiled in keep their own files there, the
    // theme file and a person's own translations, saved by the writer the interface's other
    // choices go through. One of the host's own services, which no jar reaches.
    single<HostFiles> {
        val preferences: PreferenceWriter = get()
        LauncherFiles(dataDir = get(), save = preferences::write)
    }

    // The words every owner ships, the launcher's own and every module's, as the module list
    // changes: what the language module answers its lookups from.
    single<TakenWords> { hostTakenWords(compiled, get<WidgetModules>().state, get()) }

    // What the host says outside a composition, in the words the language module answers at the
    // app level, read there each time they are asked for, since the module makes them there.
    single {
        val koin: Scope = this
        HostWords { koin.get<WidgetScope>() }
    }
    // What the engine fetches in a language, the catalogue's texts and the release notes, is
    // fetched in the language the words are in now, and in the one the settings file names
    // where the document brings no language module.
    single<ReadingLanguage> {
        val words: HostWords = get()
        val settings: ISettingsService = get()
        ReadingLanguage { words.readingOrder ?: listOf(settings.getSettings().locale) }
    }

    // What the core could not do, said in the notification centre in the person's language.
    // A pack said to be missing is taken back once a pack of that id is installed.
    single<CoreProblems> {
        val words: HostWords = get()
        NotifiedCoreProblems(notifications = get(), scope = get()) {
            words.strings(NexiraStrings.NAMESPACE)
        }.also { problems -> withdrawNoticesOfInstalledPacks(get<IPackRepository>().observe(), problems, get()) }
    }


    // Widget kernel registry. The generated object is one source among however
    // many the build carries: the kernel was always meant to accept widgets from
    // outside a single compilation (the validator's rules apply to a reflected
    // method, the processor warns rather than fails on a contract nobody here
    // provides), and the composite is the part that was never written.
    //
    // Order is precedence and the built-in registry comes first, then the widgets
    // of the modules compiled in, then the jars', so a contributed widget cannot
    // take over a kind the application depends on --
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
            compiled  = compiled,
        )
    }
    single<WidgetRegistry> { get<WidgetModules>().registry }

    // The compiled modules that are in: the ones the document names, read once, the ones those
    // and the jars switched on require, and the ones brought in since.
    single {
        val named = get<DocumentStore>().document.value.modules.toSet()
        ModulesIn(broughtIn(named, compiled, jarsRequire = get<WidgetModules>().state.value.requires))
    }

    // What the modules brought in contribute of the kinds the compiled modules take: the
    // compiled modules in and the jars switched on, as either changes. The modules the host
    // trusts read it through their host view, the catalogue module its sources.
    single<TakenContributions> {
        hostTakenContributions(
            brought  = get<ModulesIn>().ids,
            compiled = compiled,
            jars     = get<WidgetModules>().state,
            scope    = get(),
        )
    }

    // How a module the host trusts brings in a compiled module the document does not name.
    // One of the host's own services, so no jar reaches it. The levels it tells to read the
    // new keys are the app level's, there by the time any module runs.
    single<BringIn> {
        val koin: Scope = this
        val words: HostWords = get()
        HostBringIn(
            keys     = get(),
            compiled = compiled,
            modules  = get(),
            app      = { koin.getOrNull<WidgetScope>() },
            nameOf   = { moduleId, key -> words.strings(moduleId).text(key) },
        )
    }

    // Cross-widget service registry (Phase D). One global instance per
    // launcher process. Provider widgets register via provideService
    // from inside their composable body (DisposableEffect-driven
    // lifecycle); consumer widgets read via useService<T>() / its
    // siblings. Wired into the composition root from AppShell so
    // LocalWidgetServiceRegistry resolves before any @Widget renders.
    single { WidgetServiceRegistry() }

    // What the packs module needs from the application around it: the launch notices,
    // and where a missing pack is said.
    single<PacksHost> {
        val driver: LaunchDriver = get()
        ClientPacksHost(
            onLaunched  = { pack -> driver.observe(LaunchTarget(pack)) },
            indications = get(),
            problems    = get(),
            scope       = get(),
        )
    }

    // What the accounts module needs from the application around it: where a failed
    // sign-in or sign-out is said, the writer for the person's choices, and the
    // certificate question about the launcher's own server.
    single<AccountsHost> {
        ClientAccountsHost(
            problems        = get(),
            preferences     = get(),
            certificates    = get(),
            certificateHost = get<ServerProtocolConfig>().sslBypassHost,
        )
    }

    // The app's keys: the host's own, those of every compiled module the document
    // names, and those of every jar module that is switched on. Made together and
    // complete before the first widget composes, so nothing reads a registry that a
    // module has not reached yet.
    single {
        val appScope: CoroutineScope = get()
        val archive: NotificationArchiveStore = get()
        val center: NotificationCenter = get()
        val backgrounds: BackgroundManager = get()
        val koin: Scope = this
        val keys = HostKeys(
            sources = uiDataRegistry(
                activity         = get<ActivityRegistry>().activities,
                notifications    = archive.log,
                doNotDisturb     = center.doNotDisturb,
                sslBypass        = sslBypassSource(get(), get<ServerProtocolConfig>().sslBypassHost, appScope),
                background       = backgrounds.settings,
            ),
            // Reactive write side: commands widgets fire via rememberCommand /
            // rememberAction without injecting the backing service. What only some
            // hosts can do is added by the host.
            commands = uiCommandRegistry(
                clearNotifications = { archive.clear() },
                setDoNotDisturb    = { center.setDoNotDisturb(it) },
                updateBackground   = { change -> backgrounds.update(change) },
            ).apply { koin.platformCommands(this) },
            services = ContributedServices(),
        )
        val modules: WidgetModules = get()
        val words: HostWords = get()
        moduleKeys(
            keys     = keys,
            named    = get<ModulesIn>().ids.value,
            compiled = compiled,
            jars     = jarKeysOf(modules),
            onCrash  = modules::switchOffAfterCrash,
            nameOf   = { moduleId, key -> words.strings(moduleId).text(key) },
        )
        keys
    } withOptions { onClose { it?.close() } }
    single { get<HostKeys>().sources }
    single { get<HostKeys>().commands }

    // The root of the scope tree. Its app values are the registries here, and the
    // windows, compositions and pages below it are made by their owners. Under the
    // app's coroutine scope, so a level's work stops at shutdown whoever forgot it.
    // A factory that cannot make its key is said out loud: a core service that did
    // not start leaves Play doing nothing and the library empty, which nobody could
    // tell from a launcher that has no packs.
    //
    // Its services are Koin's with the services the modules declared laid over them,
    // which close with the keys, so a module's services stop with Koin.
    //
    // A jar module read again while the launcher runs answers its keys with its new
    // code, and what its old code made is dropped here as it does.
    //
    // The engine installs, updates and reattaches through the sources the catalogue
    // opened under this level, so it is handed them here, before anything is drawn.
    single {
        val appScope: CoroutineScope = get()
        val keys: HostKeys = get()
        WidgetScope.app(
            registry = get(),
            services = keys.appServices(KoinLevelServicesHost.app(this), appScope.coroutineContext),
            context  = appScope.coroutineContext,
            commands = get(),
            // The host view under each level is the launcher's alone, see LauncherHostAccess.
            access   = LauncherHostAccess,
            failures = reportedTo(get(), keys),
        ).also { app ->
            followModuleReloads(get<WidgetModules>().state, keys, app, appScope)
            followRequirements(get<WidgetModules>().state, compiled, get(), appScope)
            get<SourceWorks>().attach(sourceWorksOf(app))
        }
    }

    // Per-instance widget runtime state (rememberWidgetState): the store backs the
    // WidgetStateHost local, the GC collector prunes the state of widgets the document
    // no longer holds, and the flush hook lands the debounced write on quit. GC and the
    // flush hook are createdAtStart so they wire up before any stateful widget composes
    // and before exit.
    single { WidgetStateStore(get<Path>().resolve("widget-state.json"), get(), get()) }
    single(createdAtStart = true) {
        val presets: PresetRepository = get()
        val documents: DocumentStore = get()
        val editor: DocumentEditor = get()
        // The arrangement an older build saved stays on disk as it was, for that build to read, and so does the state
        // of its widgets. Nothing writes that file now, so it is read once.
        val olderFile = get<Path>().resolve(Storage.LAYOUT_GRAPH_FILE)
        val json: Json = get()
        val olderBuild by lazy { olderLayoutIds(olderFile, json) }
        WidgetStateGc(
            documents      = documents.document,
            writable       = { documents.writable },
            store          = get(),
            scope          = get(),
            alsoReferenced = { editor.heldIds() + presets.referencedInstanceIds() + olderBuild },
        )
    }
    single(createdAtStart = true) { WidgetStateFlushHook(get()) }

    // The wallpaper settings, one per process because the manager carries the
    // shutdown flush for its debounced writes.
    single { BackgroundManager(get<Path>(), get()) }

    // The editor's one way to change the document, and the presses the window hands to the editor and the rail.
    // It refuses a widget where what it reads has no answer, asking the scope tree's registries where each key lives.
    single {
        val appLevel: WidgetScope = get()
        val widgets: WidgetRegistry = get()
        val state: WidgetStateStore = get()
        DocumentEditor(
            store     = get(),
            scope     = get(),
            admission = DocumentAdmission(appLevel::declarationOf, widgets::get, everyPage = setOf(PageKeys.Entry.id)),
            copyState = state::copy,
        )
    }
    single { ShellSignals() }


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
    // One-slot handoff: the launch driver parks "needs a code", the accounts module answers.
    single { TwoFactorLaunchGate() }
    single {
        val settingsService: ISettingsService = get()
        val words: HostWords = get()
        val windows: CompositionWindows = get()
        LaunchDriver(
            controller      = get(),
            notifications   = get(),
            indications     = get(),
            activities      = get(),
            sessions        = get(),
            gameConsole     = get(),
            showConsole     = { windows.show(LauncherConfigs.ConsoleComposition) },
            appScope        = get(),
            offlineProvider = get(),
            settingsService = settingsService,
            credentialStore = get(),
            twoFactorGate   = get(),
            stringsProvider = { words.strings(NexiraStrings.NAMESPACE) },
        )
    }
    // Surfaces app-scoped installs (PackInstallService) into the notification
    // center. createdAtStart so its collector is live before the first install
    // can fire; start() launches on the shared app scope.
    single(createdAtStart = true) {
        val words: HostWords = get()
        InstallDriver(
            service         = get(),
            notifications   = get(),
            appScope        = get(),
            stringsProvider = { words.strings(NexiraStrings.NAMESPACE) },
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
        val words: HostWords = get()
        PackUpdateDriver(
            hub             = get(),
            repository      = get(),
            notifications   = get(),
            nav             = get(),
            appScope        = get(),
            stringsProvider = { words.strings(NexiraStrings.NAMESPACE) },
        ).also { it.start() }
    }
}

/** The widgets the arrangement an older build saved at [file] names, none where there is no such file or it will not parse. */
internal fun olderLayoutIds(file: Path, json: Json): Set<String> {
    if (!Files.exists(file)) return emptySet()
    return runCatching { instanceIdsIn(json.parseToJsonElement(readUtf8(file))) }
        .onFailure { LoggerFactory.getLogger("WidgetStateGc").warn("Could not read the widgets of {}, their state is not kept", file, it) }
        .getOrDefault(emptySet())
}

/**
 * Every factory of the scope tree that could not make its key, told to [problems]:
 * a key a module brought as that module, by its own name in the person's language,
 * and one of the host's as the part of the host it belongs to.
 */
internal fun reportedTo(problems: CoreProblems, keys: HostKeys? = null): FactoryFailures =
    FactoryFailures { id, _, cause ->
        val owner = keys?.ownerOf(id)
        val problem = if (owner != null) {
            CoreProblem.ModuleMissing(owner.moduleId, owner.name(), cause)
        } else {
            CoreProblem.PartMissing(corePartOf(id), cause)
        }
        problems.report(problem)
    }

/**
 * The part of the core the key [id] stands for, which is what a person is told is
 * missing when the key cannot be made. The id itself goes to the log only.
 */
internal fun corePartOf(id: String): CorePart = CORE_PARTS[id] ?: CorePart.Other

private val CORE_PARTS: Map<String, CorePart> = buildMap {
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
): WidgetDataRegistry = WidgetDataRegistry().apply {
    register(Sources.Activity, flowSource(activity))
    register(Sources.Notifications, flowSource(notifications))
    register(Sources.DoNotDisturb, flowSource(doNotDisturb))
    // What the window's owner provides when it opens the window: its state as the host reads it.
    declare(Sources.Window) { HostWindowState.Sample }
    register(Sources.SslBypass, sslBypass)
    register(Sources.Background, flowSource(background))
    // Read the first time a card asks rather than at startup: asking the OS is slow,
    // and most runs never open the page that shows it. The card asks while the page
    // is laid out, so the files are read off that thread.
    registerFactory(Sources.SystemInfo) { app -> flowSource(systemFactsReadOff(app.coroutineScope)) }
    // One per page, so a profile page kept in the history comes back on the section
    // it was left on.
    registerFactory(ProfileSelectionSource, sample = ::ProfileSelection) { flowSource(MutableStateFlow(ProfileSelection())) }
    // Every key here carries the sample the palette and the editor show a widget on
    // where no level of its kind is above: display data, never handed to a widget
    // drawn for use. How the launcher looks is the themes module's keys.
    registerFactory(Sources.ConsoleVisible, sample = { false }) { app ->
        val open = app.hostServices.get<CompositionWindows>().open
        val console = LauncherConfigs.ConsoleComposition
        flowSource(open.map { console in it }.stateIn(app.coroutineScope, SharingStarted.Eagerly, console in open.value))
    }
    registerFactory(WindowKeys.Open, sample = { emptySet() }) { app -> flowSource(app.hostServices.get<CompositionWindows>().open) }
    // What the composition's owner provides around every surface it draws, with the
    // sample in the sense above.
    declare(CompositionKeys.Config) { LauncherConfigs.Home }
    declare(HistoryKeys.History) { History(LauncherConfigs.Home) }
    // What the router provides for the entry each page is drawn for, and the launcher's own value of one.
    declare(PageKeys.Entry) { HistoryEntry(LauncherConfigs.Home) }
    registerFactory(PackSettingsPage.key) { page -> page.read(PackSettingsPage) }
    // What a page provides for its own widgets, on that page and nowhere else.
    declare(ProjectPageSource, ::sampleProjectPage)
    declare(CataloguePackPageSource, ::sampleCataloguePackPage)
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
): WidgetCommandRegistry = WidgetCommandRegistry().apply {
    register(Commands.ClearNotifications, command { clearNotifications() })
    register(Commands.SetDoNotDisturb, command(setDoNotDisturb))
    register(Commands.UpdateBackground, command(updateBackground))
    registerFactory(Commands.SetConsoleVisible) { app ->
        val windows = app.hostServices.get<CompositionWindows>()
        val console = LauncherConfigs.ConsoleComposition
        command { visible -> if (visible) windows.show(console) else windows.close(console) }
    }
    registerFactory(WindowKeys.Show) { app -> command(app.hostServices.get<CompositionWindows>()::show) }
    registerFactory(WindowKeys.Close) { app -> command(app.hostServices.get<CompositionWindows>()::close) }
    // What the composition's owner provides: its history.
    declare(PagesKeys.Show)
    declare(HistoryKeys.Back)
    declare(HistoryKeys.Forward)
    declare(HistoryKeys.BackTo)
    declare(HistoryKeys.Rewrite)
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

/** A module that brought keys: its id, and its name in the person's language as it is when asked. */
internal class ModuleOwner(val moduleId: String, val name: () -> String)

/**
 * The app's registries and the services modules declared, made together so a module's
 * keys are in before anything reads them, with who brought each key.
 *
 * Holds the app level's handle on the services once [appServices] made it, and closes
 * it with itself, which closes what the modules made for the app.
 */
internal class HostKeys(
    val sources: WidgetDataRegistry,
    val commands: WidgetCommandRegistry,
    val services: ContributedServices,
) : AutoCloseable {
    private var app: LevelServicesHost? = null

    private val owners = HashMap<String, ModuleOwner>()

    /** The key ids each jar module brought, by module id, and its contribution as it was when they were made. */
    internal val jars = HashMap<String, Pair<Set<String>, JarKeys>>()

    /** The module that brought the key [id], or null for one of the host's own. */
    @Synchronized
    fun ownerOf(id: String): ModuleOwner? = owners[id]

    @Synchronized
    internal fun own(ids: Set<String>, owner: ModuleOwner) = ids.forEach { owners[it] = owner }

    /** The app level's services: [koin] with the modules' services laid over it. Made once. */
    @Synchronized
    fun appServices(koin: LevelServicesHost, context: CoroutineContext): LevelServicesHost =
        app ?: services.host(koin, context).also { app = it }

    @Synchronized
    override fun close() {
        app?.close()
        app = null
    }
}

/**
 * The key contribution [contributionId] of the jar module [moduleId], whose name is
 * [name], as [current] answers it now: null once the module is switched off or gone,
 * another instance once the folder was read again.
 */
internal class JarKeys(
    val moduleId: String,
    val name: String,
    val contributionId: String,
    val current: () -> KeyContribution?,
)

/** The key contributions of every jar module [modules] has loaded, each followed as the modules change. */
internal fun jarKeysOf(modules: WidgetModules): List<JarKeys> =
    modules.state.value.of<KeyContribution>().map { loaded ->
        JarKeys(loaded.moduleId, loaded.moduleName, loaded.item.id) {
            modules.state.value.of<KeyContribution>().firstOrNull { it.moduleId == loaded.moduleId && it.item.id == loaded.item.id }?.item
        }
    }


/**
 * Registers into [keys] the keys of the modules the launcher brings in:
 *
 * - every module compiled into the build, out of [compiled], that [named] names. A
 *   module the document does not name brings no keys, which leaves a widget that
 *   needs one misplaced rather than drawn on nothing. These are trusted with the
 *   host's own services.
 * - every jar module in [jars], which are the ones switched on in the module list.
 *   Switching a jar on is how a person declares it, so it needs no name in the
 *   document as well. A jar is not trusted with the host's services.
 *
 * A name nothing here carries is logged and otherwise left alone, since its jar may
 * simply not be installed. A module whose keys cannot all be registered registers
 * none and is logged by name. One whose own code threw while declaring them is handed
 * to [onCrash], the way a module that crashed the interface is, which switches it off.
 *
 * [nameOf] answers a module's name, a key into its words, in the person's language,
 * for what is said about the module when one of its keys cannot be made.
 *
 * A jar's keys are registered at startup: one switched on later brings them at the
 * next start. One read again while the launcher runs answers them with its new code,
 * and one switched off leaves them missing, see [followModuleReloads].
 */
internal fun moduleKeys(
    keys: HostKeys,
    named: Set<String>,
    compiled: List<CompiledModule>,
    jars: List<JarKeys>,
    nameOf: (moduleId: String, key: String) -> String = { _, key -> key },
    onCrash: (moduleId: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    val carried = compiled.map { it.id }.toSet() + jars.map { it.moduleId }
    (named - carried).forEach { moduleKeysLog.warn("The interface names the module '{}', which this launcher does not carry", it) }
    compiled.filter { it.id in named }.forEach { module -> compiledModuleKeys(keys, module, nameOf) }
    jars.forEach { jar ->
        val result = registerModuleKeys(jar.moduleId, jar.current, keys.sources, keys.commands, keys.services, trusted = false)
        settle(keys, jar.moduleId, ModuleOwner(jar.moduleId) { nameOf(jar.moduleId, jar.name) }, result) { cause -> onCrash(jar.moduleId, cause) }
        if (result is ModuleKeysResult.Registered) {
            synchronized(keys) { keys.jars[jar.moduleId + "/" + jar.contributionId] = result.ids to jar }
        }
    }
}

/**
 * Registers into [keys] the keys of the compiled [module], trusted with the host's own services, each key
 * contribution on its own, and answers what came of each. The one way a compiled module's keys go in,
 * whether the document names it ([moduleKeys]) or it is brought in later ([HostBringIn]), so both go
 * through the same checks.
 */
internal fun compiledModuleKeys(
    keys: HostKeys,
    module: CompiledModule,
    nameOf: (moduleId: String, key: String) -> String,
): List<ModuleKeysResult> {
    val owner = ModuleOwner(module.id) { nameOf(module.id, module.name) }
    return module.contributions.filterIsInstance<KeyContribution>().map { contribution ->
        registerModuleKeys(module.id, contribution, keys.sources, keys.commands, keys.services, trusted = true).also { result ->
            settle(keys, module.id, owner, result) { /* a compiled module cannot be switched off */ }
        }
    }
}

private fun settle(keys: HostKeys, moduleId: String, owner: ModuleOwner, result: ModuleKeysResult, crashed: (Throwable) -> Unit) {
    when (result) {
        is ModuleKeysResult.Registered -> keys.own(result.ids, owner)
        is ModuleKeysResult.Refused -> {
            moduleKeysLog.error("Module '{}' brought no keys: {}", moduleId, result.reason, result.cause)
            result.cause?.let(crashed)
        }
    }
}

/**
 * Drops what [app]'s levels made from a jar module's keys whenever [modules] carry
 * another instance of its contribution, a reload or a switch off, so nothing goes on
 * running code of a class loader the module list is about to close. The next read
 * makes the key afresh from the module as it is then, or finds it missing.
 */
internal fun followModuleReloads(modules: StateFlow<WidgetModules.Snapshot>, keys: HostKeys, app: WidgetScope, scope: CoroutineScope) {
    val followed = synchronized(keys) { keys.jars.values.toList() }
    if (followed.isEmpty()) return
    val seen = HashMap<String, KeyContribution?>()
    followed.forEach { (_, jar) -> seen[jar.moduleId + "/" + jar.contributionId] = jar.current() }
    scope.launch {
        modules.collect {
            followed.forEach { (ids, jar) ->
                val at = jar.moduleId + "/" + jar.contributionId
                val now = jar.current()
                if (now !== seen[at]) {
                    seen[at] = now
                    app.forgetMade(ids)
                }
            }
        }
    }
}

/**
 * Brings in what the jars of [modules] require of [compiled] as they change, a jar switched on while the
 * launcher runs say, so what it requires is there for what it contributes at once.
 */
internal fun followRequirements(modules: StateFlow<WidgetModules.Snapshot>, compiled: List<CompiledModule>, bringIn: BringIn, scope: CoroutineScope) {
    val graph = moduleGraph(compiled)
    scope.launch {
        modules.collect { now -> now.requires.mapNotNull(graph::providerOf).distinct().forEach { val _ = bringIn.bringIn(it) } }
    }
}

private val moduleKeysLog = LoggerFactory.getLogger("ModuleKeys")
