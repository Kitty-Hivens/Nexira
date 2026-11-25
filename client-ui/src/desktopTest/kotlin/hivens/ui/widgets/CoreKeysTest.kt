package hivens.ui.widgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.accounts.AccountsHost
import hivens.accounts.AccountsModule
import hivens.accounts.SessionStore
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountsApi
import hivens.accounts.api.SessionState
import hivens.accounts.api.SignInError
import hivens.accounts.api.SignInFlow
import hivens.auth.AccountStore
import hivens.auth.AuthCapabilities
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.StoredAccount
import hivens.catalogue.CatalogueModule
import hivens.catalogue.api.CatalogueApi
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceFilterOption
import hivens.catalogue.api.SourceView
import hivens.config.Storage
import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import hivens.core.launch.LaunchState
import hivens.core.net.TransferEngine
import hivens.core.security.SslBypassEntry
import hivens.core.security.SslBypassStore
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.i18n.I18nModule
import hivens.i18n.I18nSettingsDomain
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.launcher.InstallSnapshot
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.network.CertificateTrustGate
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.packs.PackActions
import hivens.packs.PackOutcomes
import hivens.packs.PacksHost
import hivens.packs.PacksModule
import hivens.packs.ReattachPreviewState
import hivens.packs.ReattachPreviews
import hivens.packs.api.InstallRequest
import hivens.packs.api.OperationStatus
import hivens.packs.api.PackKeys
import hivens.packs.api.PackLaunch
import hivens.packs.api.PackOperationView
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import hivens.packs.api.PacksApi
import hivens.packs.api.ReattachConfirmation as ConfirmationView
import hivens.packs.api.ReattachPreviewView
import hivens.packs.api.ReattachRequest
import hivens.packs.toPackView
import hivens.packs.toView
import hivens.test.stopThenDelete
import hivens.themes.SystemScheme
import hivens.themes.ThemeLibraryFile
import hivens.themes.ThemeManager
import hivens.themes.Themes
import hivens.themes.ThemesModule
import hivens.themes.ThemesSettingsDomain
import hivens.themes.api.ThemeInputs
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.ui.HostKeys
import hivens.ui.accounts.ClientAccountsHost
import hivens.ui.background.BackgroundSettings
import hivens.ui.host.HostWords
import hivens.ui.host.LauncherFiles
import hivens.ui.host.LauncherHostAccess
import hivens.ui.host.launcherServices
import hivens.ui.i18n.NexiraStrings
import hivens.ui.levelServicesModule
import hivens.ui.moduleKeys
import hivens.ui.notifications.CorePart
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.NotifiedCoreProblems
import hivens.ui.notifications.PersistedNotification
import hivens.ui.onEventThread
import hivens.ui.packs.ClientPacksHost
import hivens.ui.packs.withdrawNoticesOfInstalledPacks
import hivens.ui.reportedTo
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.settle
import hivens.themes.api.WallpaperTone
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.PreferenceWriter
import hivens.ui.widgets.modules.fixedTakenWords
import hivens.ui.widgets.modules.launcherWords
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostFiles
import hivens.widget.api.KeyContribution
import hivens.widget.api.KeyOrigin
import hivens.widget.api.LevelServices
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.TakenWords
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.registerModuleKeys
import hivens.widget.api.rememberCommand
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The launcher's core data and actions as sources and commands: each resolves at
 * the level its key declares, made by that level from the registry's factory out of
 * the level's services, with nothing of the shell drawn, and each command does what
 * the screen or the shell used to do itself. What cannot be done is said out loud.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CoreKeysTest {

    private val dir: Path = Files.createTempDirectory("core-keys-test")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val consoles = ArrayList<GameConsoleService>()

    private val koins = ArrayList<KoinApplication>()

    private val hostKeys = ArrayList<HostKeys>()

    private val writers = ArrayList<PreferenceWriter>()

    @AfterTest
    fun tearDown() {
        // A test may have closed its own already. The keys go first: the modules'
        // services close with them, the startup sign-in's retries among them.
        hostKeys.forEach { keys -> val _ = runCatching { keys.close() } }
        koins.forEach { koin -> val _ = runCatching { koin.close() } }
        runBlocking { consoles.forEach { it.close() } }
        // The saves a test asked for land in the folder about to be deleted, so they land first.
        stopThenDelete(scope, dir, settle = { writers.forEach { it.drain(5.seconds) } })
    }

    // ── Stand-ins ─────────────────────────────────────────────────────

    private class Settings(initial: SettingsData) : ISettingsService {
        @Volatile var current: SettingsData = initial
        @Volatile var failWrites = false
        override fun getSettings(): SettingsData = current
        override fun saveSettings(settings: SettingsData) {
            current = settings
        }
        @Synchronized
        override fun updateSettings(transform: (SettingsData) -> SettingsData): SettingsData {
            if (failWrites) error("the settings file is locked")
            return transform(current).also { current = it }
        }
    }

    /** An account store that can be told to fail, to hold the startup sign-in, and that counts its reads. */
    private class Accounts(private val saved: SessionData? = null) : AccountStore {
        @Volatile var cleared = false
        @Volatile var failRead = false
        @Volatile var failClear = false
        @Volatile var hold: CountDownLatch? = null
        val entered = CountDownLatch(1)
        val reads = AtomicInteger()
        override fun load(): SessionData? = saved
        override fun accountFor(providerId: String): SessionData? = saved
        override fun refreshStored(providerId: String, session: SessionData) = Unit
        override fun saveAccount(session: SessionData, providerId: String, makeActive: Boolean) = Unit
        override fun markTwoFactor(providerId: String) = Unit
        override fun clearTwoFactor(providerId: String) = Unit
        override fun listAccounts(): List<StoredAccount> = emptyList()
        override fun activeAccountId(): String? = null
        override fun primarySession(preferredProviderId: String?): SessionData? {
            reads.incrementAndGet()
            entered.countDown()
            hold?.await(5, TimeUnit.SECONDS)
            if (failRead) error("the keyring is locked")
            return if (cleared) null else saved
        }
        override fun loadSession(providerId: String, accountId: String): SessionData? = saved
        override fun setActive(accountId: String) = Unit
        override fun removeAccount(providerId: String, accountId: String) = Unit
        override fun clear() {
            if (failClear) error("the keyring is locked")
            cleared = true
        }
    }

    private class Grants : SslBypassStore {
        override val bypasses = MutableStateFlow<List<SslBypassEntry>>(emptyList())
        override fun isBypassed(host: String): Boolean = false
        override fun grant(host: String, until: Instant) = Unit
        override fun revoke(host: String) = Unit
    }

    private class Packs(list: List<PackInstance>) : IPackRepository {
        private val all = MutableStateFlow(list)
        override fun observe(): StateFlow<List<PackInstance>> = all
        override suspend fun list(): List<PackInstance> = all.value
        override suspend fun get(id: String): PackInstance? = all.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {
            all.value = all.value.filterNot { it.id == instance.id } + instance
        }
        override suspend fun delete(id: String) {
            all.value = all.value.filterNot { it.id == id }
        }
    }

    /** What the launcher's controller and services were asked to do, by the commands. */
    private class RecordingActions : PackActions {
        val launched = CopyOnWriteArrayList<Pair<SessionData, PackInstance>>()
        val stopped = CopyOnWriteArrayList<String>()
        val installed = CopyOnWriteArrayList<Pair<CataloguePack, CataloguePackVersion>>()
        val dismissed = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val updated = CopyOnWriteArrayList<PackInstance>()
        val deleted = CopyOnWriteArrayList<PackInstance>()
        val reattached = CopyOnWriteArrayList<Pair<PackInstance, ReattachConfirmation>>()
        override val launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
        override val installs = MutableStateFlow<Map<String, InstallSnapshot>>(emptyMap())
        override fun launch(session: SessionData, pack: PackInstance): Boolean {
            launched += session to pack
            return true
        }
        override fun stop(packId: String) {
            stopped += packId
        }
        override fun install(pack: CataloguePack, version: CataloguePackVersion) {
            installed += pack to version
        }
        override fun dismissInstall(key: String) {
            dismissed += key
        }
        override fun cancelInstall(key: String) {
            cancelled += key
        }
        override fun update(pack: PackInstance) {
            updated += pack
        }
        override suspend fun delete(pack: PackInstance): DeleteOutcome {
            deleted += pack
            return DeleteOutcome.Deleted
        }
        override fun reattach(pack: PackInstance, confirmed: ReattachConfirmation) {
            reattached += pack to confirmed
        }
    }

    private class Problems : CoreProblems {
        val reported = CopyOnWriteArrayList<CoreProblem>()
        val withdrawn = CopyOnWriteArrayList<KClass<out CoreProblem>>()
        override fun report(problem: CoreProblem) {
            reported += problem
        }
        override fun withdraw(kind: KClass<out CoreProblem>) {
            withdrawn += kind
        }
        override fun withdraw(problem: CoreProblem) {
            withdrawn += problem::class
        }
    }

    /**
     * A source a module of the test's own brings, the way a third party would: one pack
     * with one build, and a filter of its own over its list.
     */
    private object Shelf : PackSourceContribution, PackListing {
        override val id: String = "Shelf"
        override val label: String = "shelf.name"
        override val paged: Boolean = false
        override val filters: List<SourceFilter> =
            listOf(SourceFilter("shelf", "shelf.filter", listOf(SourceFilterOption("dusty", "shelf.dusty"), SourceFilterOption("new", "shelf.new"))))

        /** How many times it was opened, across every launcher the tests stand up. */
        val opened = AtomicInteger()

        override fun open(services: LevelServices): PackListing = this.also { opened.incrementAndGet() }
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = emptyList()
        override suspend fun details(packId: String): PackDetails = when (packId) {
            "industrial" -> PackDetails(id, "industrial", "Industrial", "A pack", versions = listOf(BUILD))
            // A pack whose page needs a class the source's jar does not carry.
            "unlinked" -> throw NoClassDefFoundError("hivens/shelf/Missing")
            else -> throw IOException("no such pack")
        }
        override suspend fun versions(packId: String): List<ProjectBuild> = details(packId).versions

        val BUILD = ProjectBuild("v5", "5", "5", "release", emptyList(), emptyList(), "")

        /** The build as the engine's installer is handed it. */
        val INSTALLED = CataloguePackVersion("v5", "5", "5")

        /** What the module that brings it is called by: the namespace of its words. */
        const val MODULE = "test.shelf"

        val taken = object : TakenContributions {
            override val all = MutableStateFlow(
                listOf<TakenContribution<ModuleContribution>>(TakenContribution(MODULE, Shelf), TakenContribution("test.cracked", Cracked)),
            )
        }
    }

    /**
     * A source whose module was built against another launcher: its declaration cannot
     * link, so the catalogue leaves it out, and nothing installs from it either, though it
     * would answer for the same pack.
     */
    private object Cracked : PackSourceContribution {
        override val id: String = "Cracked"
        override val label: String = "cracked.name"
        override val filters: List<SourceFilter> get() = throw NoClassDefFoundError("hivens/catalogue/api/SomethingNewer")
        override fun open(services: LevelServices): PackListing = Shelf
    }

    private class Hub : PackUpdateStatusHub {
        override val statuses = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
        override fun report(id: String, status: PackUpdateStatus) {
            statuses.value += id to status
        }
    }

    /** What the stand-in source answers for any pack a reattach is previewed for. */
    private val shownPreview = ReattachPreview(
        build = "5",
        toVersion = "5",
        compat = CompatChange.Same,
        removed = listOf("mods/mine.jar"),
        restored = emptyList(),
        replaced = emptyList(),
    )

    /** Work on the packs as the operation service would publish it. */
    private val operations = MutableStateFlow<Map<String, PackOperation>>(emptyMap())

    private val pack = PackInstance(
        id = "p1",
        packRef = PackReference(PackOrigin(Shelf.id), "industrial", "5"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 1L,
    )

    /**
     * The launcher's Koin with stand-ins under the services the factories read, the
     * launcher's own level module, and nothing of any shell. The packs and accounts
     * modules bring their keys the way the launcher brings them, through their key
     * contributions, for a document that names them, which [withPacks],
     * [withAccounts], [withCatalogue] and [withThemes] false do not. With [withActions]
     * false the pack actions are missing, the way a broken install would leave them.
     *
     * [settings] is also what the launcher's former settings file holds, which the
     * modules' settings domains take their first values from.
     */
    private inner class Launcher(
        settings: SettingsData = SettingsData(),
        saved: SessionData? = null,
        withActions: Boolean = true,
        withPacks: Boolean = true,
        withAccounts: Boolean = true,
        withCatalogue: Boolean = true,
        withI18n: Boolean = true,
        withThemes: Boolean = true,
        notified: Boolean = false,
        /** What signs a stored account in at startup. */
        auth: AuthProvider = OfflineAuthProvider(),
        configure: Accounts.() -> Unit = {},
    ) {
        val settings = Settings(settings)
        val accounts = Accounts(saved).apply(configure)
        val actions = RecordingActions()
        val problems = Problems()

        /**
         * What reached the notification history, when [notified] puts the launcher's
         * own reporter in. It pushes on the reporting thread, so a report has landed by
         * the time the call that made it returns, and nothing arrives after.
         */
        val archived = CopyOnWriteArrayList<PersistedNotification>()
        val center = NotificationCenter(archive = { archived += it })
        val reporter: CoreProblems =
            if (notified) NotifiedCoreProblems(center, scope, Dispatchers.Unconfined) { NexiraStrings.shipped("en") } else problems
        val packs = Packs(listOf(pack))
        val measured = AtomicInteger()
        val previews = ReattachPreviews(measure = { measured.incrementAndGet(); shownPreview }, operations = operations, scope = scope)
        val preferences = PreferenceWriter("core-keys-test").also { writers += it }
        val indications = IndicationCenter()
        val hub = Hub()
        val data = uiDataRegistry(
            activity         = MutableStateFlow(emptyList()),
            notifications    = MutableStateFlow(emptyList()),
            doNotDisturb     = MutableStateFlow(false),
            sslBypass        = flowSource(MutableStateFlow(false)),
            background       = MutableStateFlow(BackgroundSettings()),
        )
        val commands = uiCommandRegistry({}, {}, {})

        /** The modules' settings, written where the launcher writes them, under the test's data folder. */
        val domains = SettingsDomainFiles(dir.resolve(Storage.SETTINGS_DIR), dir.resolve(Storage.SETTINGS_FILE), preferences::write).also {
            Files.writeString(dir.resolve(Storage.SETTINGS_FILE), legacyJson.encodeToString(SettingsData.serializer(), settings))
        }
        val compiled = listOf(I18nModule, ThemesModule, PacksModule, AccountsModule, CatalogueModule)

        /** What the host says outside a composition, in the words the language module answers at the app level, as the launcher's Koin makes it. */
        val words = HostWords { app }
        val keys = HostKeys(data, commands, ContributedServices()).also { keys ->
            moduleKeys(
                keys     = keys,
                named    = setOfNotNull(
                    PacksApi.ID.takeIf { withPacks }, AccountsApi.ID.takeIf { withAccounts },
                    CatalogueApi.ID.takeIf { withCatalogue }, I18nApi.ID.takeIf { withI18n },
                    ThemesApi.ID.takeIf { withThemes },
                ),
                compiled = compiled,
                jars     = emptyList(),
                nameOf   = { moduleId, key -> words.strings(moduleId).text(key) },
            )
        }.also { hostKeys += it }
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        val console = GameConsoleService(paths).also { consoles += it }

        val application: KoinApplication = koinApplication {
            modules(
                levelServicesModule,
                module {
                    single<CoroutineScope> { scope }
                    single<ISettingsService> { this@Launcher.settings }
                    single<AccountStore> { accounts }
                    single<AuthProvider> { auth }
                    single { OfflineAuthProvider() }
                    single { AuthProviderRegistry(emptyList()) }
                    single<SslBypassStore> { Grants() }
                    single<CoreProblems> { reporter }
                    single<IPackRepository> { packs }
                    if (withActions) single<PackActions> { actions }
                    single { previews }
                    single { PackOutcomes(operations, scope) }
                    single<PackUpdateStatusHub> { hub }
                    single<TakenContributions> { Shelf.taken }
                    single { paths }
                    // Stood up and never reached: whatever a page asks of the network fails.
                    single {
                        val offline = HttpClientProvider { error("no network in a keys test") }
                        ModrinthClient(offline, TransferEngine(offline))
                    }
                    single { indications }
                    single<PacksHost> { ClientPacksHost({}, indications, reporter, scope) }
                    single<AccountsHost> { ClientAccountsHost(reporter, preferences, CertificateTrustGate(Grants()), "smartycraft.example") }
                    single { console }
                    single<SettingsStores> { domains }
                    single<HostFiles> { LauncherFiles(dir, preferences::write) }
                    single<TakenWords> { fixedTakenWords(launcherWords(compiled)) }
                    single {
                        val services = keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined)
                        WidgetScope.app(data, services, Dispatchers.Unconfined, commands, access = LauncherHostAccess, failures = reportedTo(reporter, keys))
                    }
                },
            )
        }.also { koins += it }

        val koin: Koin = application.koin

        val app: WidgetScope = koin.get()

        /** A window and a composition in it, the way a host opens one. */
        fun composition(): WidgetScope = app.child(ScopeLevel.Window).child(ScopeLevel.Composition)

        fun <T> source(level: WidgetScope, key: SourceKey<T>): T = assertNotNull(level.current(key), "${key.id} has no answer at ${level.level}")

        fun <P> fire(level: WidgetScope, key: CommandKey<P>, payload: P) =
            assertNotNull(level.findCommand(key), "${key.id} has no answer at ${level.level}").run(payload)

        /** The themes module's manager, made the way the first read of one of its keys makes it. */
        val themes: ThemeManager get() = app.launcherServices.get<ThemeManager>()

        /** The app's session store, the accounts module's, made the way the first read of the session makes it. */
        val session: SessionStore get() = app.launcherServices.get<SessionStore>()

        /**
         * Waits for the work the app level's commands launched so far. The app level
         * runs on the test thread until its work suspends, and the commands return
         * nothing to wait on, so what they left running is joined here.
         */
        fun drain() = runBlocking {
            withTimeout(5.seconds) { app.coroutineScope.coroutineContext.job.children.toList().forEach { it.join() } }
        }

        /** What only the host tells the themes module, reached the way the host reaches it. */
        val inputs: ThemeInputs get() = assertNotNull(app.launcherServices.find(ThemeInputs::class), "the host cannot reach the theme inputs")

        /** The launcher's former settings file as it is now, once every save asked for has landed. */
        fun legacy(): SettingsData {
            preferences.drain(5.seconds)
            return legacyJson.decodeFromString(SettingsData.serializer(), Files.readString(dir.resolve(Storage.SETTINGS_FILE)))
        }
    }

    private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A provider whose server is never reached, the way a network that is down answers a sign-in. */
    private class Unreachable : AuthProvider {
        override val id: String = "unreachable"
        override val displayName: String = "Unreachable"
        override val capabilities = AuthCapabilities(supports2FA = false)
        override suspend fun login(username: String, password: String, serverId: String): SessionData =
            throw IOException("no route to host")
        override suspend fun completeTwoFactor(username: String, password: String, serverId: String, uid: String, code: String): SessionData =
            throw IOException("no route to host")
    }

    /** A stored account the startup sign-in logs in again with its saved password. */
    private val stored = SessionData(playerName = "Steve", accessToken = "token", cachedPassword = "secret")

    private fun <T> await(flow: StateFlow<T>, what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(5.seconds) { flow.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${flow.value}", e)
        }
    }

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!check()) {
            if (System.nanoTime() > until) throw AssertionError("$what never happened")
            Thread.sleep(10)
        }
    }

    private fun settled(launcher: Launcher): SessionState = await(launcher.session.state, "the startup sign-in") { it !is SessionState.Resolving }

    /** The name the launcher is signed in as, or null. */
    private fun SessionState?.name(): String? = (this as? SessionState.SignedIn)?.session?.name

    // ── Resolution ────────────────────────────────────────────────────

    /** Every source the themes module brings. */
    private val themeSources: List<SourceKey<*>> = listOf(
        ThemesKeys.Settings, ThemesKeys.Active, ThemesKeys.Offered, ThemesKeys.Dark, ThemesKeys.Mode, ThemesKeys.SystemSchemeAvailable,
    )

    /** Every command the themes module brings. */
    private val themeCommands: List<CommandKey<*>> = listOf(
        ThemesKeys.UpdateSettings, ThemesKeys.Choose, ThemesKeys.SetDark, ThemesKeys.SetMode,
    )

    /** Every source the accounts module brings. */
    private val accountSources: List<SourceKey<*>> = listOf(
        AccountKeys.Session, AccountKeys.Accounts, AccountKeys.Providers, AccountKeys.Face, AccountKeys.Remember,
        AccountKeys.SignIns, AccountKeys.SecondFactor, AccountKeys.SkinUploads,
    )

    /** Every command the accounts module brings. */
    private val accountCommands: List<CommandKey<*>> = listOf(
        AccountKeys.SignInSmartyCraft, AccountKeys.SignInMicrosoft, AccountKeys.SignInOffline, AccountKeys.SignInCode,
        AccountKeys.CancelSignIn, AccountKeys.SignOut, AccountKeys.RemoveAccount, AccountKeys.SetFace, AccountKeys.SetRemember,
        AccountKeys.AnswerSecondFactor, AccountKeys.DismissSecondFactor, AccountKeys.UploadSkin,
    )

    private val appWide: List<SourceKey<*>> = listOf(
        PackKeys.Library, PackKeys.Launches,
        PackKeys.Launch, PackKeys.Installs, PackKeys.Updates, PackKeys.Operations, PackKeys.ReattachPreviews,
        Sources.ConsoleVisible, I18nKeys.Settings, I18nKeys.Language, I18nKeys.Languages, I18nKeys.Words,
    ) + accountSources + themeSources

    private val appCommands: List<CommandKey<*>> = listOf(
        PackKeys.LaunchPack, PackKeys.StopPack, PackKeys.InstallPack,
        PackKeys.DismissInstall, PackKeys.CancelInstall, PackKeys.UpdatePack, PackKeys.DeletePack, PackKeys.PreviewReattach,
        PackKeys.ReattachPack, Commands.SetConsoleVisible, I18nKeys.UpdateSettings,
    ) + accountCommands + themeCommands

    /** Every key the packs module brings, by id. */
    private val packIds: List<String> = listOf(
        PackKeys.LIBRARY_ID, PackKeys.LAUNCHES_ID, PackKeys.LAUNCH_ID, PackKeys.INSTALLS_ID, PackKeys.UPDATES_ID,
        PackKeys.OPERATIONS_ID, PackKeys.REATTACH_PREVIEWS_ID, PackKeys.LAUNCH_PACK_ID, PackKeys.STOP_PACK_ID,
        PackKeys.INSTALL_PACK_ID, PackKeys.DISMISS_INSTALL_ID, PackKeys.CANCEL_INSTALL_ID, PackKeys.UPDATE_PACK_ID,
        PackKeys.DELETE_PACK_ID, PackKeys.PREVIEW_REATTACH_ID, PackKeys.REATTACH_PACK_ID,
    )

    @Test
    fun `every core key is made by a factory at its level and answers there with no shell drawn`() {
        val launcher = Launcher()
        val page = launcher.composition().child(ScopeLevel.Page)
        appWide.forEach { key ->
            val declared = assertNotNull(launcher.app.declarationOf(key.id), "${key.id} is not declared")
            assertEquals(KeyOrigin.Factory, declared.origin, "${key.id} is not made by a factory")
            assertEquals(key.level, declared.level)
            assertNotNull(page.find(key), "${key.id} is not read from a page below its level")
            assertNotNull(launcher.app.sampleOf(key.id), "${key.id} has no sample to be shown on")
        }
        appWide.forEach { key -> assertNotNull(launcher.app.find(key), "${key.id} has no answer at the app") }
        appCommands.forEach { key ->
            val declared = assertNotNull(launcher.app.declarationOf(key.id), "${key.id} is not declared")
            assertEquals(KeyOrigin.Factory, declared.origin, "${key.id} is not made by a factory")
            assertEquals(key.level, declared.level)
            assertNotNull(page.findCommand(key), "${key.id} is not fired from a page below its level")
        }
        assertEquals(listOf(pack.toPackView()), launcher.source(page, PackKeys.Library))
        assertSame(launcher.themes.active.value, launcher.source(page, ThemesKeys.Active))
        assertTrue(launcher.problems.reported.isEmpty(), "nothing failed: ${launcher.problems.reported}")
    }

    @Test
    fun `a core service that cannot be made is one notice in the person's words, however many keys and windows`() {
        val launcher = Launcher(withActions = false, notified = true)
        assertNull(launcher.app.find(PackKeys.Launch))
        assertNull(launcher.app.findCommand(PackKeys.StopPack))
        assertNull(launcher.app.findCommand(PackKeys.LaunchPack))
        assertNull(launcher.app.find(PackKeys.Installs))
        val group = launcher.center.groups.value.single()
        assertEquals("core:module:${PacksApi.ID}", group.sourceKey, "told as the module that brought the keys")
        assertEquals("Packs did not start", group.events.single().title, "under the module's own name")
        assertTrue(group.events.none { "launch.state" in it.title || "pack.stop" in it.title }, "key ids stay in the log")
        assertEquals(1, launcher.archived.size, "one record in the history")
    }

    @Test
    fun `one cause reported from many threads at once is one notice`() {
        val archived = CopyOnWriteArrayList<PersistedNotification>()
        val center = NotificationCenter(archive = { archived += it })
        // Pushed on the reporting thread, so every report has landed once its thread is joined.
        val reporter = NotifiedCoreProblems(center, scope, Dispatchers.Unconfined) { NexiraStrings.shipped("en") }
        val start = CyclicBarrier(16)
        val threads = (1..16).map { n ->
            thread {
                start.await(5, TimeUnit.SECONDS)
                reporter.report(CoreProblem.PartMissing(CorePart.Session, IllegalStateException("broken $n")))
            }
        }
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive }, "a reporting thread never finished")
        assertEquals(1, archived.size, "the same cause was told more than once")
        assertEquals(1, center.groups.value.single().events.size)
    }

    // ── Session ───────────────────────────────────────────────────────

    @Test
    fun `every composition reads the app's one session, which signs in once`() {
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve"))
        val first = launcher.composition()
        val second = launcher.composition()
        val sampled = second.child(ScopeLevel.Page)
        settled(launcher)
        listOf(first, second, sampled).forEach { level ->
            assertSame(launcher.session, level.launcherServices.find<SessionStore>(), "no level below the app makes a store of its own")
        }
        assertEquals("Steve", launcher.source(first, AccountKeys.Session).name())
        launcher.session.signIn(SessionData(playerName = "Alex"))
        assertEquals("Alex", launcher.source(second, AccountKeys.Session).name())
        launcher.composition()
        assertEquals(1, launcher.accounts.reads.get(), "a new composition does not sign in again")
    }

    @Test
    fun `startup signs in the chosen offline name, and with nothing to sign in with leaves the launcher signed out`() {
        val named = Launcher(SettingsData(offlinePlayerName = "Steve"))
        assertEquals("Steve", settled(named).name())
        assertIs<SessionState.SignedOut>(settled(Launcher()))
    }

    @Test
    fun `a sign-in made by hand while the startup one is in flight stays`() {
        ActionRing.clear()
        val hold = CountDownLatch(1)
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve")) { this.hold = hold }
        val store = launcher.session
        assertTrue(launcher.accounts.entered.await(5, TimeUnit.SECONDS), "the startup sign-in never reached the store")
        launcher.session.signIn(SessionData(playerName = "Alex"))
        hold.countDown()
        awaitTrue("the startup sign-in standing down") {
            ActionRing.snapshot().any { it.text.contains("the manual one stays") }
        }
        assertEquals("Alex", store.state.value.name())
    }

    @Test
    fun `a sign-in made by hand while the startup one finds nothing to sign in with stays`() {
        ActionRing.clear()
        val hold = CountDownLatch(1)
        val launcher = Launcher { this.hold = hold }
        val store = launcher.session
        assertTrue(launcher.accounts.entered.await(5, TimeUnit.SECONDS), "the startup sign-in never reached the store")
        launcher.session.signIn(SessionData(playerName = "Alex"))
        hold.countDown()
        awaitTrue("the startup sign-in standing down") {
            ActionRing.snapshot().any { it.text.contains("found no session after a manual sign-in") }
        }
        assertEquals("Alex", store.state.value.name())
    }

    @Test
    fun `a network that is down leaves the sign-in form usable and keeps trying`() {
        ActionRing.clear()
        val launcher = Launcher(saved = stored, auth = Unreachable())
        assertIs<SessionState.SignedOut>(settled(launcher), "loading covers the first attempt only")
        awaitTrue("a retry planned") { ActionRing.snapshot().any { it.text.contains("network down, retry #1") } }
        assertTrue(launcher.problems.reported.isEmpty(), "a network that is down is not a failure of the launcher")
    }

    @Test
    fun `a sign-in made by hand while the network is down stays`() {
        ActionRing.clear()
        val hold = CountDownLatch(1)
        val launcher = Launcher(saved = stored, auth = Unreachable()) { this.hold = hold }
        val store = launcher.session
        assertTrue(launcher.accounts.entered.await(5, TimeUnit.SECONDS), "the startup sign-in never reached the store")
        launcher.session.signIn(SessionData(playerName = "Alex"))
        hold.countDown()
        awaitTrue("the network failure handled") { ActionRing.snapshot().any { it.text.contains("network down, retry #1") } }
        assertEquals("Alex", store.state.value.name())
    }

    @Test
    fun `a startup sign-in that throws leaves the launcher signed out and says so`() {
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve")) { failRead = true }
        assertIs<SessionState.SignedOut>(settled(launcher))
        assertIs<CoreProblem.SignInFailed>(launcher.problems.reported.single())
    }

    @Test
    fun `a sign-in that works takes back the notice that one failed`() {
        val launcher = Launcher(notified = true) { failRead = true }
        settled(launcher)
        awaitTrue("the sign-in failure notice") { launcher.center.groups.value.any { it.sourceKey == "core:sign-in" } }
        launcher.session.signIn(SessionData(playerName = "Alex"))
        awaitTrue("the notice taken back") { launcher.center.groups.value.none { it.sourceKey == "core:sign-in" } }
    }

    @Test
    fun `sign-out clears every account and the names at once`() {
        val launcher = Launcher(SettingsData(preferredFaceProvider = "microsoft"))
        settled(launcher)
        launcher.session.signIn(SessionData(playerName = "Alex"))
        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Alex")
        launcher.fire(launcher.app, AccountKeys.SignOut, Unit)
        await(launcher.session.state, "the sign-out") { it is SessionState.SignedOut }
        assertTrue(launcher.accounts.cleared)
        assertNull(launcher.settings.current.preferredFaceProvider)
        assertNull(launcher.settings.current.offlinePlayerName)
        assertTrue(launcher.problems.reported.isEmpty())
    }

    @Test
    fun `a sign-out that cannot clear the accounts stays signed in and says so`() {
        val launcher = Launcher { failClear = true }
        settled(launcher)
        launcher.session.signIn(SessionData(playerName = "Alex"))
        // The whole sign-out, finished, before anything is looked at.
        runBlocking { withTimeout(5.seconds) { launcher.session.signOut().join() } }
        assertIs<CoreProblem.SignOutFailed>(launcher.problems.reported.single())
        assertIs<SessionState.SignedIn>(launcher.session.state.value, "the accounts are still there, so the session is")
    }

    @Test
    fun `a sign-out that cleared the accounts but not the offline name signs out and says the name was kept`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.session.signIn(SessionData(playerName = "Alex"))
        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Alex")
        launcher.settings.failWrites = true
        runBlocking { withTimeout(5.seconds) { launcher.session.signOut().join() } }
        assertIs<SessionState.SignedOut>(launcher.session.state.value)
        assertTrue(launcher.accounts.cleared)
        assertEquals("Alex", launcher.settings.current.offlinePlayerName, "the name really is still there")
        assertIs<CoreProblem.SignedOutNameKept>(launcher.problems.reported.single(), "it says what happened, not that sign-out failed")
    }

    // A notice the person dismissed said nothing about the next attempt, and one that
    // stood said the opposite of what the next attempt did.
    @Test
    fun `every failed sign-out is told, and one that works takes the notice back`() {
        val launcher = Launcher(notified = true) { failClear = true }
        settled(launcher)
        launcher.session.signIn(SessionData(playerName = "Alex"))
        fun signOut() = runBlocking { withTimeout(5.seconds) { launcher.session.signOut().join() } }
        fun standing() = launcher.center.groups.value.any { it.sourceKey == "core:sign-out" }

        // The notice is pushed from the reporter's own coroutine, which may land after
        // the sign-out's job has ended, so each one is waited for, not read at once.
        signOut()
        awaitTrue("the first failure told") { standing() }
        launcher.center.dismiss("core:sign-out")
        signOut()
        awaitTrue("the second failure told too") { standing() }
        awaitTrue("both in the history") { launcher.archived.size == 2 }

        launcher.accounts.failClear = false
        signOut()
        awaitTrue("a sign-out that worked leaves no word that it could not") { !standing() }
        assertIs<SessionState.SignedOut>(launcher.session.state.value)
        assertEquals(2, launcher.archived.size, "and adds nothing to the history")
    }

    @Test
    fun `the session store is closed with the app, and does nothing after`() {
        val launcher = Launcher()
        val store = launcher.session
        settled(launcher)
        // The app's services close with the keys, the way the launcher's Koin closes them.
        launcher.keys.close()
        val signOut = store.signOut()
        runBlocking { withTimeout(5.seconds) { signOut.join() } }
        assertTrue(signOut.isCancelled, "a closed store started the sign-out anyway")
        assertFalse(launcher.accounts.cleared, "a closed store still cleared the accounts")
    }

    // ── Packs ─────────────────────────────────────────────────────────

    @Test
    fun `launch runs as the launcher's session, and offline as the chosen name when nobody is signed in`() {
        val launcher = Launcher()
        settled(launcher)

        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Steve")
        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        awaitTrue("the offline launch") { launcher.actions.launched.isNotEmpty() }
        val (offline, launchedPack) = launcher.actions.launched.single()
        assertEquals("Steve", offline.playerName)
        assertTrue(offline.offline)
        assertEquals(pack, launchedPack)

        launcher.session.signIn(SessionData(playerName = "Alex"))
        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        assertEquals("Alex", launcher.actions.launched.last().first.playerName, "a signed-in launch runs as the session, at once")
    }

    @Test
    fun `launching a pack that is not installed fails out loud`() {
        val launcher = Launcher()
        launcher.fire(launcher.app, PackKeys.LaunchPack, "not-installed")
        assertEquals(listOf<CoreProblem>(CoreProblem.PackNotInstalled("not-installed")), launcher.problems.reported.toList())
        assertTrue(launcher.actions.launched.isEmpty())
    }

    // Under a key of its own, so taking it back cannot take the launch driver's
    // notices for the pack with it, and told each time, since each is a press of Play.
    @Test
    fun `a pack that is not installed is told under its own key each time it is launched`() {
        val launcher = Launcher(notified = true)
        launcher.fire(launcher.app, PackKeys.LaunchPack, "not-installed")
        launcher.center.dismiss("core:pack:not-installed:not-installed")
        launcher.fire(launcher.app, PackKeys.LaunchPack, "not-installed")
        assertEquals(listOf("core:pack:not-installed:not-installed"), launcher.center.groups.value.map { it.sourceKey })
        assertEquals(2, launcher.archived.size)
    }

    // The launcher's own Play reads "signing in" and does nothing while the launcher
    // finds out who it is. A launch started at once ran under the offline name in
    // front of the account about to sign in, and one kept waiting showed nothing and
    // could wait for good.
    @Test
    fun `a launch asked for while the startup sign-in runs is refused, as Play is`() {
        val hold = CountDownLatch(1)
        val account = SessionData(playerName = "Alex", accessToken = "token", twoFactor = true)
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve"), saved = account) { this.hold = hold }
        val store = launcher.session
        assertTrue(launcher.accounts.entered.await(5, TimeUnit.SECONDS), "the startup sign-in never reached the store")
        assertIs<SessionState.Resolving>(store.state.value)

        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        hold.countDown()
        assertIs<SessionState.SignedIn>(settled(launcher))
        launcher.drain()
        assertTrue(launcher.actions.launched.isEmpty(), "nothing started, then or once the sign-in settled")

        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        assertEquals("Alex", launcher.actions.launched.single().first.playerName, "a press after it runs as the account")
    }

    // The notice is sticky, and says something that stops being true.
    @Test
    fun `a pack said to be missing is taken back once it launches`() {
        val launcher = Launcher(notified = true)
        settled(launcher)
        launcher.fire(launcher.app, PackKeys.LaunchPack, "later")
        assertTrue(launcher.center.groups.value.any { it.sourceKey == "core:pack:later:not-installed" })

        runBlocking { launcher.packs.put(pack.copy(id = "later", instanceDirName = "later")) }
        launcher.session.signIn(SessionData(playerName = "Alex"))
        launcher.fire(launcher.app, PackKeys.LaunchPack, "later")
        assertEquals("later", launcher.actions.launched.single().second.id)
        assertTrue(launcher.center.groups.value.none { it.sourceKey == "core:pack:later:not-installed" })
    }

    @Test
    fun `a pack said to be missing is taken back once it is installed`() {
        val launcher = Launcher(notified = true)
        withdrawNoticesOfInstalledPacks(launcher.packs.observe(), launcher.reporter, scope)
        launcher.fire(launcher.app, PackKeys.LaunchPack, "later")
        assertTrue(launcher.center.groups.value.any { it.sourceKey == "core:pack:later:not-installed" })

        runBlocking { launcher.packs.put(pack.copy(id = "later", instanceDirName = "later")) }
        awaitTrue("the notice taken back") { launcher.center.groups.value.none { it.sourceKey == "core:pack:later:not-installed" } }
    }

    @Test
    fun `nobody signed in and no offline name starts nothing`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        launcher.drain()
        assertTrue(launcher.actions.launched.isEmpty())
    }

    @Test
    fun `stop, install, update, delete and reattach reach the launcher's actions with the pack they name`() {
        val launcher = Launcher()
        val app = launcher.app
        val confirmation = ConfirmationView(shownPreview.confirmation.build, shownPreview.confirmation.fingerprint)
        launcher.fire(app, PackKeys.StopPack, pack.id)
        val opened = Shelf.opened.get()
        // A source that cannot link while answering is logged, and nothing reaches the
        // thread's last handler, which in the launcher is the crash reporter. The app level
        // runs on this thread until its work suspends, so this is where it would land.
        val thread = Thread.currentThread()
        val lastHandler = thread.uncaughtExceptionHandler
        val uncaught = CopyOnWriteArrayList<Throwable>()
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            launcher.fire(app, PackKeys.InstallPack, InstallRequest(Shelf.id, "unlinked", "v5"))
            launcher.drain()
        } finally {
            thread.uncaughtExceptionHandler = lastHandler
        }
        assertEquals(emptyList(), uncaught.map { it.javaClass.simpleName }, "the install caught what the source could not link")
        launcher.fire(app, PackKeys.InstallPack, InstallRequest(Shelf.id, "industrial", "v5"))
        launcher.fire(app, PackKeys.InstallPack, InstallRequest(Shelf.id, "industrial", "no-such-build"))
        launcher.fire(app, PackKeys.InstallPack, InstallRequest(Shelf.id, "no-such-pack", "v5"))
        // The id exactly as the record carries it, and a source the catalogue refused is no source.
        launcher.fire(app, PackKeys.InstallPack, InstallRequest(Shelf.id.lowercase(), "industrial", "v5"))
        launcher.fire(app, PackKeys.InstallPack, InstallRequest(Cracked.id, "industrial", "v5"))
        launcher.fire(app, PackKeys.InstallPack, InstallRequest("no-such-source", "industrial", "v5"))
        launcher.fire(app, PackKeys.DismissInstall, "k1")
        launcher.fire(app, PackKeys.CancelInstall, "k2")
        launcher.fire(app, PackKeys.UpdatePack, pack.id)
        launcher.fire(app, PackKeys.DeletePack, pack.id)
        launcher.fire(app, PackKeys.ReattachPack, ReattachRequest(pack.id, confirmation))
        launcher.fire(app, PackKeys.UpdatePack, "not-installed")
        launcher.fire(app, PackKeys.DeletePack, "not-installed")
        launcher.fire(app, PackKeys.ReattachPack, ReattachRequest("not-installed", confirmation))
        launcher.drain()
        assertEquals(listOf(pack.id), launcher.actions.stopped)
        assertEquals(
            listOf(CataloguePack(PackOrigin(Shelf.id), "industrial", "Industrial", "A pack") to Shelf.INSTALLED),
            launcher.actions.installed.toList(),
            "the build the request names, looked up in its source, and nothing for a request the source cannot answer",
        )
        assertEquals(opened + 1, Shelf.opened.get(), "every install reads the one listing the catalogue opened")
        assertEquals(listOf("k1"), launcher.actions.dismissed)
        assertEquals(listOf("k2"), launcher.actions.cancelled)
        assertEquals(listOf(pack), launcher.actions.updated)
        assertEquals(listOf(pack to shownPreview.confirmation), launcher.actions.reattached, "with the preview the player agreed to")
        assertEquals(listOf(pack), launcher.actions.deleted)
        assertEquals(
            PackOperationView("delete", OperationStatus.Done()),
            launcher.source(app, PackKeys.Operations)[pack.id],
            "the delete's outcome is read back, since the command gives none",
        )
    }

    @Test
    fun `waiting updates and the work on each pack read as the packs API's plain data`() {
        val launcher = Launcher()
        val app = launcher.app
        launcher.hub.report(pack.id, PackUpdateStatus.Pending("6", UpdateDirection.Older, CompatChange.McBump, held = true))
        awaitTrue("the waiting build") { launcher.source(app, PackKeys.Updates)[pack.id] != null }
        assertEquals(
            PackUpdateView.Pending("6", PackUpdateView.Pending.OLDER, "mc-bump", held = true),
            launcher.source(app, PackKeys.Updates)[pack.id],
        )
        operations.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Update, PackOperationPhase.Running(3, 9, "a.jar")))
        awaitTrue("the update under way") { launcher.source(app, PackKeys.Operations)[pack.id] != null }
        assertEquals(PackOperationView("update", OperationStatus.Running(3, 9)), launcher.source(app, PackKeys.Operations)[pack.id])
        operations.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Update, PackOperationPhase.Failed("disk full")))
        awaitTrue("the update's failure") { launcher.source(app, PackKeys.Operations)[pack.id]?.status is OperationStatus.Failed }
        assertEquals(PackOperationView("update", OperationStatus.Failed("failed", "disk full")), launcher.source(app, PackKeys.Operations)[pack.id])
    }

    @Test
    fun `a document that does not name the packs module has none of its keys, and nothing is said to be broken`() {
        val launcher = Launcher(withPacks = false, notified = true)
        val page = launcher.composition().child(ScopeLevel.Page)
        packIds.forEach { id -> assertNull(launcher.app.declarationOf(id), "$id is declared without the module") }
        assertNull(page.find(PackKeys.Library))
        assertNull(page.findCommand(PackKeys.LaunchPack))
        assertNotNull(page.find(Sources.ConsoleVisible), "the host's own keys are there all the same")
        assertNotNull(page.find(AccountKeys.Session), "and the accounts module's, which it names")
        assertTrue(launcher.center.groups.value.isEmpty(), "a module left out is not a part that failed: ${launcher.center.groups.value}")
    }

    @Test
    fun `a document that does not name the accounts module has no session keys, nothing is said broken, and a launch runs as nobody`() {
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve"), withAccounts = false, notified = true)
        val page = launcher.composition().child(ScopeLevel.Page)
        (accountSources.map { it.id } + accountCommands.map { it.id }).forEach { id ->
            assertNull(launcher.app.declarationOf(id), "$id is declared without the module")
        }
        assertNull(page.find(AccountKeys.Session))
        assertNull(page.findCommand(AccountKeys.SignOut))
        assertNull(launcher.app.launcherServices.find(SessionStore::class), "and nothing keeps a session")

        launcher.fire(launcher.app, PackKeys.LaunchPack, pack.id)
        awaitTrue("the offline launch") { launcher.actions.launched.isNotEmpty() }
        val (session, _) = launcher.actions.launched.single()
        assertEquals("Steve", session.playerName, "nobody is signed in, so it runs as the offline name")
        assertTrue(session.offline)
        assertTrue(launcher.center.groups.value.isEmpty(), "a module left out is not a part that failed: ${launcher.center.groups.value}")
    }

    // ── Catalogue ─────────────────────────────────────────────────────

    /** Every key the catalogue module brings, by id. */
    private val catalogueIds: List<String> = listOf(
        CatalogueKeys.TAGS_ID, CatalogueKeys.LOAD_TAGS_ID, CatalogueKeys.SOURCES_ID, CatalogueKeys.BROWSE_ID, CatalogueKeys.RESULTS_ID,
        CatalogueKeys.OPEN_PROJECT_ID, CatalogueKeys.NAMES_ID, CatalogueKeys.INSTALL_CHOICE_ID, CatalogueKeys.SET_KIND_ID,
        CatalogueKeys.SET_ORIGIN_ID, CatalogueKeys.SET_QUERY_ID, CatalogueKeys.SET_SORT_ID, CatalogueKeys.SET_TARGET_ID,
        CatalogueKeys.AIM_ID, CatalogueKeys.TOGGLE_FILTER_ID, CatalogueKeys.EXCLUDE_FILTER_ID, CatalogueKeys.REMOVE_FILTER_ID,
        CatalogueKeys.CLEAR_FILTERS_ID, CatalogueKeys.SEARCH_FOR_ID, CatalogueKeys.UNLOCK_ID, CatalogueKeys.RELOCK_ID,
        CatalogueKeys.HIDE_INSTALLED_ID, CatalogueKeys.SOURCE_OPTION_ID,
        CatalogueKeys.TOGGLE_HIDE_INSTALLED_ID, CatalogueKeys.TOGGLE_SOURCE_OPTION_ID,
        CatalogueKeys.MORE_RESULTS_ID, CatalogueKeys.RETRY_RESULTS_ID, CatalogueKeys.SHOW_RESULTS_ID, CatalogueKeys.HIDE_RESULTS_ID, CatalogueKeys.SCROLL_RESULTS_ID,
        CatalogueKeys.INSTALL_RESULT_ID, CatalogueKeys.CHOOSE_INSTALL_ID, CatalogueKeys.INSTALL_INTO_ID,
        CatalogueKeys.INSTALL_NEW_PACK_ID, CatalogueKeys.RETRY_CHOICE_ID, CatalogueKeys.DISMISS_INSTALL_ID,
        CatalogueKeys.PROJECT_ID, CatalogueKeys.BUILD_ID, CatalogueKeys.PACK_ID, CatalogueKeys.SHOW_PROJECT_ID,
        CatalogueKeys.HIDE_PROJECT_ID, CatalogueKeys.RELOAD_PROJECT_ID, CatalogueKeys.LOAD_VERSIONS_ID,
        CatalogueKeys.RETRY_VERSIONS_ID, CatalogueKeys.INSTALL_PROJECT_ID, CatalogueKeys.RETRY_INSTALL_ID,
        CatalogueKeys.SHOW_BUILD_ID, CatalogueKeys.SHOW_PACK_ID, CatalogueKeys.HIDE_PACK_ID, CatalogueKeys.RETRY_PACK_ID,
    )

    @Test
    fun `a document that does not name the catalogue module has no catalogue keys, and nothing is said to be broken`() {
        val launcher = Launcher(withCatalogue = false, notified = true)
        val page = launcher.composition().child(ScopeLevel.Page)
        catalogueIds.forEach { id -> assertNull(launcher.app.declarationOf(id), "$id is declared without the module") }
        assertNull(page.find(CatalogueKeys.Browse))
        assertNull(page.find(CatalogueKeys.Results))
        assertNull(page.find(CatalogueKeys.OpenProject))
        assertNull(page.find(CatalogueKeys.ProjectPage))
        assertNull(page.findCommand(CatalogueKeys.SetQuery))
        assertNull(page.findCommand(CatalogueKeys.ShowProject))
        assertNotNull(page.find(PackKeys.Library), "the modules it names are there all the same")
        assertNotNull(page.find(Sources.ConsoleVisible), "and the host's own keys")
        assertTrue(launcher.center.groups.value.isEmpty(), "a module left out is not a part that failed: ${launcher.center.groups.value}")
    }

    @Test
    fun `the catalogue's keys answer at their levels, one browse per window and one page per page`() {
        val launcher = Launcher()
        catalogueIds.forEach { id ->
            val declared = assertNotNull(launcher.app.declarationOf(id), "$id is not declared")
            assertEquals(KeyOrigin.Factory, declared.origin, "$id is not made by a factory")
        }
        val window = launcher.app.child(ScopeLevel.Window)
        val other = launcher.app.child(ScopeLevel.Window)
        val composition = window.child(ScopeLevel.Composition)
        val page = composition.child(ScopeLevel.Page)
        val sibling = composition.child(ScopeLevel.Page)

        launcher.fire(page, CatalogueKeys.SetQuery, "sodium")
        assertEquals("sodium", launcher.source(window, CatalogueKeys.Browse).query, "a page fires into its window's browse")
        assertEquals("", launcher.source(other, CatalogueKeys.Browse).query, "a second window browses on its own")
        assertSame(window.find(CatalogueKeys.Browse), page.find(CatalogueKeys.Browse), "a page below a window reads that window's")
        assertEquals(
            listOf(SourceView(Shelf.id, Shelf.MODULE, Shelf.label, filters = Shelf.filters)),
            launcher.source(window, CatalogueKeys.Browse).sources,
            "the sources the modules brought in, as each declares itself",
        )
        assertEquals(launcher.source(window, CatalogueKeys.Browse).sources, launcher.source(launcher.app, CatalogueKeys.Sources))

        assertNull(page.current(CatalogueKeys.ProjectPage), "a page shows no project until one is shown")
        assertTrue(page.find(CatalogueKeys.ProjectPage) !== sibling.find(CatalogueKeys.ProjectPage), "two pages do not share one")
        assertTrue(launcher.problems.reported.isEmpty(), "nothing failed: ${launcher.problems.reported}")
    }

    @Test
    fun `offline play through its command signs in under the name and keeps it for the next start`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.fire(launcher.app, AccountKeys.SignInOffline, " Steve ")
        await(launcher.session.state, "the offline sign-in") { it.name() == "Steve" }
        val signed = assertIs<SessionState.SignedIn>(launcher.session.state.value).session
        assertTrue(signed.offline)
        assertEquals(AccountKeys.OFFLINE, signed.providerKey)
        assertEquals("Steve", launcher.settings.current.offlinePlayerName)
        assertEquals(null, launcher.source(launcher.app, AccountKeys.SignIns)[AccountKeys.OFFLINE], "nothing is left under way")
    }

    @Test
    fun `offline play with no name fails as empty and signs nobody in`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.fire(launcher.app, AccountKeys.SignInOffline, "  ")
        val flow = launcher.source(launcher.app, AccountKeys.SignIns)[AccountKeys.OFFLINE]
        assertEquals(SignInFlow.Failed(SignInError(SignInError.EMPTY)), flow)
        assertIs<SessionState.SignedOut>(launcher.session.state.value)
        launcher.fire(launcher.app, AccountKeys.CancelSignIn, AccountKeys.OFFLINE)
        assertTrue(launcher.source(launcher.app, AccountKeys.SignIns).isEmpty(), "the failure is forgotten once read")
    }

    /**
     * A widget gets a reattach preview the way the settings screen does. Work on the
     * pack may change the files it measured, so while that work runs the preview
     * reads as comparing and once it ends it is measured again. Dropped instead, the
     * dialog that asked for it waited on a comparison nobody was running.
     */
    @Test
    fun `a reattach preview is asked for by command, read from its source, and measured again after work on the pack`() {
        val launcher = Launcher()
        val app = launcher.app
        launcher.fire(app, PackKeys.PreviewReattach, pack.id)
        launcher.fire(app, PackKeys.PreviewReattach, "not-installed")
        awaitTrue("the preview") { launcher.source(app, PackKeys.ReattachPreviews)[pack.id] is ReattachPreviewView.Ready }
        assertEquals(
            mapOf(pack.id to ReattachPreviewState.Ready(shownPreview).toView()),
            launcher.source(app, PackKeys.ReattachPreviews),
        )

        assertEquals(1, launcher.measured.get())

        operations.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Repair, PackOperationPhase.Running(0, 0, "")))
        awaitTrue("comparing while the work runs") { launcher.source(app, PackKeys.ReattachPreviews)[pack.id] == ReattachPreviewView.Comparing }
        operations.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Repair, PackOperationPhase.Running(5, 9, "a.jar")))
        operations.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Repair, PackOperationPhase.Repaired(1, 0)))
        // The end is the last change sent, and the collector takes changes in order, so
        // once the end has been measured nothing is left that could measure again.
        awaitTrue("measured again once it ended") {
            launcher.measured.get() == 2 && launcher.source(app, PackKeys.ReattachPreviews)[pack.id] is ReattachPreviewView.Ready
        }
        assertEquals(2, launcher.measured.get(), "progress within the work is not news")

        launcher.previews.forget(pack.id)
        awaitTrue("and gone once nobody wants it") { launcher.source(app, PackKeys.ReattachPreviews).isEmpty() }
    }

    /**
     * Work dismissed before its end was seen left a widget's preview comparing for
     * good, and a preview nobody forgot kept its pack's archive until the next start.
     */
    @Test
    fun `work dismissed early still ends, and a preview nobody forgets goes with what it kept`() {
        val ops = MutableStateFlow<Map<String, PackOperation>>(emptyMap())
        val measured = AtomicInteger()
        val discarded = CopyOnWriteArrayList<String>()
        val store = ReattachPreviews(
            measure  = { measured.incrementAndGet(); shownPreview },
            operations = ops,
            scope    = scope,
            discard  = { discarded += it.id },
            keepFor  = 600.milliseconds,
        )
        store.request(pack)
        awaitTrue("the preview") { store.previews.value[pack.id] is ReattachPreviewState.Ready }

        ops.value = mapOf(pack.id to PackOperation(pack.id, PackOperationKind.Repair, PackOperationPhase.Running(0, 0, "")))
        awaitTrue("comparing while it runs") { store.previews.value[pack.id] == ReattachPreviewState.Comparing }
        ops.value = emptyMap()
        awaitTrue("measured again though its end was never seen") {
            measured.get() == 2 && store.previews.value[pack.id] is ReattachPreviewState.Ready
        }

        awaitTrue("gone a while after it answered") { store.previews.value.isEmpty() }
        awaitTrue("and what it kept let go") { discarded.toList() == listOf(pack.id) }
    }

    @Test
    fun `the launches source lists the packs in flight and drops one that ended`() {
        val launcher = Launcher()
        launcher.indications.setLaunchIndication(pack.id, LaunchIndication.Running)
        awaitTrue("the pack in flight") { launcher.source(launcher.app, PackKeys.Launches) == mapOf(pack.id to PackLaunch.Running) }
        launcher.indications.setLaunchIndication(pack.id, null)
        awaitTrue("the pack dropped once it ended") { launcher.source(launcher.app, PackKeys.Launches).isEmpty() }
    }

    // ── Appearance and console ────────────────────────────────────────

    @Test
    fun `the themes module's commands change the one choice every composition reads, and save it`() {
        val launcher = Launcher(SettingsData(isDarkTheme = true, themeMode = ThemeMode.System.name, locale = "en"))
        val composition = launcher.composition()
        val other = launcher.composition()
        val theme = Themes.all.last().id

        launcher.fire(composition, ThemesKeys.Choose, theme)
        assertEquals(theme, launcher.source(other, ThemesKeys.Active).id, "another composition reads the same choice")
        assertEquals(theme, launcher.source(other, ThemesKeys.Settings).theme)

        launcher.fire(composition, ThemesKeys.SetDark, false)
        assertFalse(launcher.source(other, ThemesKeys.Dark))
        assertEquals(ThemeMode.Manual, launcher.source(other, ThemesKeys.Mode), "a choice made by hand takes the scheme back from the system")

        launcher.fire(composition, ThemesKeys.SetMode, ThemeMode.Wallpaper)
        launcher.inputs.wallpaper(WallpaperTone(listOf(0xFF102030.toInt()), avgLuminance = 0.2f))
        assertTrue(launcher.source(composition, ThemesKeys.Dark), "a dark wallpaper turns the wallpaper mode dark")
        assertEquals(ThemeMode.Wallpaper, launcher.source(composition, ThemesKeys.Mode))

        launcher.fire(launcher.app, I18nKeys.UpdateSettings, SettingsChange { it.copy(language = "de") })
        assertEquals("de", launcher.source(other, I18nKeys.Settings).language, "every composition reads the one choice")
        awaitTrue("the words follow it") { launcher.source(other, I18nKeys.Language).tag == "de" }

        assertFalse(launcher.source(launcher.app, ThemesKeys.SystemSchemeAvailable), "no test reads the system's scheme")
        launcher.themes.systemScheme(SystemScheme(readable = true, dark = false))
        assertTrue(launcher.source(launcher.app, ThemesKeys.SystemSchemeAvailable), "what the module reads of the system is offered")

        val saved = launcher.legacy()
        assertTrue(saved.isDarkTheme, "the old file holds the scheme for a build from before the move")
        assertEquals(ThemeMode.Wallpaper.name, saved.themeMode)
        assertTrue(saved.themeFromWallpaper)
        assertEquals("de", saved.locale, "and the language, mirrored the same way")
        val domains = SettingsDomainFiles(dir.resolve(Storage.SETTINGS_DIR), dir.resolve(Storage.SETTINGS_FILE))
        assertEquals("de", domains.peek(I18nSettingsDomain).language)
        assertEquals(theme, domains.peek(ThemesSettingsDomain).theme, "the choice is the themes module's own")
        assertEquals(theme, ThemeLibraryFile(dir) { _, _ -> }.load().library.selected, "and copied to the theme file for an older build")
    }

    // The test above changes the mode after the dark choice, and the mode writes the
    // scheme over whatever the choice saved.
    @Test
    fun `a dark or light choice made by hand is saved for the next start`() {
        val launcher = Launcher(SettingsData(isDarkTheme = true, themeMode = ThemeMode.System.name, themeFromWallpaper = false))
        launcher.fire(launcher.composition(), ThemesKeys.SetDark, false)
        val saved = launcher.legacy()
        assertFalse(saved.isDarkTheme)
        assertEquals(ThemeMode.Manual.name, saved.themeMode)
        assertFalse(saved.themeFromWallpaper)
    }

    @Test
    fun `turning the wallpaper mode on applies the brightness the wallpaper last reported`() {
        val launcher = Launcher(SettingsData(isDarkTheme = false, themeMode = ThemeMode.Manual.name))
        val composition = launcher.composition()
        launcher.inputs.wallpaper(WallpaperTone(listOf(0xFF102030.toInt()), avgLuminance = 0.2f))
        assertFalse(launcher.source(composition, ThemesKeys.Dark), "the person's own choice ignores the wallpaper")
        launcher.fire(composition, ThemesKeys.SetMode, ThemeMode.Wallpaper)
        assertTrue(launcher.source(composition, ThemesKeys.Dark), "the dark wallpaper it already knew decides at once")
    }

    @Test
    fun `what the wallpaper shows is the host's to tell, and a jar cannot reach it`() {
        val launcher = Launcher()
        val reached = SourceKey<Boolean>("probe.themeInputs")
        val jar = object : KeyContribution {
            override val id = "probe.keys"
            override val label = "probe"
            override fun declare(keys: ModuleKeys) {
                keys.source(reached) { app -> flowSource(MutableStateFlow(app.services.find(ThemeInputs::class) != null)) }
            }
        }
        val result = registerModuleKeys("probe", jar, launcher.data, launcher.commands, launcher.keys.services, trusted = false)
        assertIs<ModuleKeysResult.Registered>(result)

        assertFalse(launcher.source(launcher.app, reached), "a jar's code found the theme inputs")
        assertNull(launcher.app.services.find(ThemeInputs::class), "nor does a widget's view of the level")
        assertNotNull(launcher.app.launcherServices.find(ThemeInputs::class), "the host reaches them")
    }

    @Test
    fun `a document that does not name the themes module has none of its keys, and nothing keeps a theme`() {
        val launcher = Launcher(withThemes = false, notified = true)
        val page = launcher.composition().child(ScopeLevel.Page)
        (themeSources.map { it.id } + themeCommands.map { it.id }).forEach { id ->
            assertNull(launcher.app.declarationOf(id), "$id is declared without the module")
        }
        assertNull(page.find(ThemesKeys.Active))
        assertNull(page.findCommand(ThemesKeys.Choose))
        assertNull(launcher.app.launcherServices.find(ThemeInputs::class), "nor the inputs the module keeps for the host")
        assertNull(launcher.app.launcherServices.find(ThemeManager::class), "nor a manager: the shell draws its fallback, see LookValuesTest")
        assertTrue(launcher.center.groups.value.isEmpty(), "a module left out is not a part that failed: ${launcher.center.groups.value}")
    }

    @Test
    fun `the console source follows the console, and the command shows and hides it`() {
        val launcher = Launcher()
        assertFalse(launcher.source(launcher.app, Sources.ConsoleVisible))
        launcher.fire(launcher.app, Commands.SetConsoleVisible, true)
        assertTrue(launcher.console.shouldShowConsole)
        assertTrue(launcher.source(launcher.app, Sources.ConsoleVisible))
        launcher.console.hide()
        assertFalse(launcher.source(launcher.app, Sources.ConsoleVisible))
    }

    // ── Read and fired by a widget ────────────────────────────────────

    @Test
    fun `a widget under a window and a composition reads and fires them with nothing else drawn`() {
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve"))
        var packs: List<PackView>? = null
        var session: SessionState? = null
        var dark: Boolean? = null
        var launch: ((String) -> Unit)? = null
        var stop: ((String) -> Unit)? = null
        var show: (() -> Unit)? = null
        onEventThread {
            val scene = ImageComposeScene(100, 100, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides launcher.data,
                    LocalWidgetCommandRegistry provides launcher.commands,
                    LocalScope provides launcher.app,
                ) {
                    ChildLevel(ScopeLevel.Window) {
                        ChildLevel(ScopeLevel.Composition) {
                            packs = rememberScopedSource(PackKeys.Library).valueOrNull()
                            session = rememberScopedSource(AccountKeys.Session).valueOrNull()
                            dark = rememberScopedSource(ThemesKeys.Dark).valueOrNull()
                            launch = rememberScopedCommand(PackKeys.LaunchPack)
                            // The plain app read, which a command a factory makes answers too.
                            stop = rememberCommand(PackKeys.StopPack)
                            val setConsole = rememberCommand(Commands.SetConsoleVisible)
                            show = { setConsole(true) }
                        }
                    }
                }
            }
            try {
                scene.settle(4)
                // The startup sign-in runs off the event thread, and lands a few frames later.
                var waited = 0
                while (session !is SessionState.SignedIn && waited < 250) {
                    Thread.sleep(20)
                    Snapshot.sendApplyNotifications()
                    scene.settle(1)
                    waited++
                }
            } finally {
                scene.close()
            }
        }
        assertEquals(listOf(pack.toPackView()), packs)
        assertEquals("Steve", session.name())
        assertEquals(true, dark)
        assertNotNull(launch).invoke(pack.id)
        assertEquals("Steve", launcher.actions.launched.single().first.playerName)
        assertNotNull(stop).invoke(pack.id)
        assertEquals(listOf(pack.id), launcher.actions.stopped)
        assertNotNull(show).invoke()
        assertTrue(launcher.console.shouldShowConsole)
    }
}
