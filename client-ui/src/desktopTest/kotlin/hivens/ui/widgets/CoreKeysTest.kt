package hivens.ui.widgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.StoredAccount
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.data.ThemeMode
import hivens.core.diag.ActionRing
import hivens.core.launch.LaunchState
import hivens.core.security.SslBypassEntry
import hivens.core.security.SslBypassStore
import hivens.launcher.InstallSnapshot
import hivens.launcher.platform.PlatformPaths
import hivens.ui.AppState
import hivens.ui.appearance.AppearanceStore
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.levelServicesModule
import hivens.ui.notifications.CorePart
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.NotifiedCoreProblems
import hivens.ui.notifications.PersistedNotification
import hivens.ui.onEventThread
import hivens.ui.packs.PackActions
import hivens.ui.packs.PackInstallRequest
import hivens.ui.reportedTo
import hivens.ui.session.SessionStore
import hivens.ui.sessionModule
import hivens.ui.settle
import hivens.ui.theme.ThemeManager
import hivens.ui.theme.Themes
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.PreferenceWriter
import hivens.widget.api.KeyOrigin
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.find
import hivens.widget.api.flowSource
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.reflect.KClass
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        // A test may have closed its own already.
        koins.forEach { koin -> val _ = runCatching { koin.close() } }
        runBlocking { consoles.forEach { it.close() } }
        scope.cancel()
        dir.deleteRecursively()
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
        val installed = CopyOnWriteArrayList<PackInstallRequest>()
        val dismissed = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val updated = CopyOnWriteArrayList<PackInstance>()
        val deleted = CopyOnWriteArrayList<PackInstance>()
        override val launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
        override val installs = MutableStateFlow<Map<String, InstallSnapshot>>(emptyMap())
        override fun launch(session: SessionData, pack: PackInstance): Boolean {
            launched += session to pack
            return true
        }
        override fun stop(packId: String) {
            stopped += packId
        }
        override fun install(request: PackInstallRequest) {
            installed += request
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
        override suspend fun delete(pack: PackInstance) {
            deleted += pack
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
    }

    private val pack = PackInstance(
        id = "p1",
        packRef = PackReference(PackOrigin.Mirror, "industrial", "5"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 1L,
    )

    private val data = uiDataRegistry(
        activity         = MutableStateFlow(emptyList()),
        notifications    = MutableStateFlow(emptyList()),
        doNotDisturb     = MutableStateFlow(false),
        sslBypass        = flowSource(MutableStateFlow(false)),
        background       = MutableStateFlow(BackgroundSettings()),
        accountsRevision = MutableStateFlow(0),
    )

    private val commands = uiCommandRegistry({}, {}, {}, {})

    /**
     * The launcher's Koin with stand-ins under the services the factories read, the
     * launcher's own level and session modules, and nothing of any shell. With
     * [withActions] false the pack actions are missing, the way a broken install
     * would leave them.
     */
    private inner class Launcher(
        settings: SettingsData = SettingsData(),
        saved: SessionData? = null,
        withActions: Boolean = true,
        notified: Boolean = false,
        configure: Accounts.() -> Unit = {},
    ) {
        val settings = Settings(settings)
        val accounts = Accounts(saved).apply(configure)
        val actions = RecordingActions()
        val problems = Problems()

        /** What reached the notification history, when [notified] puts the launcher's own reporter in. */
        val archived = CopyOnWriteArrayList<PersistedNotification>()
        val center = NotificationCenter(archive = { archived += it })
        val reporter: CoreProblems =
            if (notified) NotifiedCoreProblems(center, scope) { stringsFor(AppLocale.ENGLISH) } else problems
        val preferences = PreferenceWriter("core-keys-test")
        val indications = IndicationCenter()
        val console = GameConsoleService(
            PlatformPaths(
                osName = "linux",
                home = dir,
                bootstrapDataDir = { null },
                env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
            ),
        ).also { consoles += it }
        val appearance = AppearanceStore(
            settings    = this.settings,
            preferences = preferences,
            themes      = ThemeManager(dir) { file, content ->
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            },
            contributed = MutableStateFlow(emptyList()),
            scope       = scope,
        )

        val application: KoinApplication = koinApplication {
            modules(
                levelServicesModule,
                sessionModule(),
                module {
                    single<CoroutineScope> { scope }
                    single<ISettingsService> { this@Launcher.settings }
                    single<AccountStore> { accounts }
                    single<AuthProvider> { OfflineAuthProvider() }
                    single { OfflineAuthProvider() }
                    single { AuthProviderRegistry(emptyList()) }
                    single<SslBypassStore> { Grants() }
                    single<CoreProblems> { reporter }
                    single<IPackRepository> { Packs(listOf(pack)) }
                    if (withActions) single<PackActions> { actions }
                    single { indications }
                    single { console }
                    single { appearance }
                    single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands, reportedTo(reporter)) }
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

        /** The app's session store, made the way the first read of the session makes it. */
        val session: SessionStore get() = koin.get()
    }

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

    private fun settled(launcher: Launcher): AppState = await(launcher.session.state, "the startup sign-in") { it !is AppState.Loading }

    // ── Resolution ────────────────────────────────────────────────────

    private val composed: List<SourceKey<*>> = listOf(
        Sources.ThemeLibrary, Sources.DarkTheme, Sources.ThemeMode, Sources.Locale,
    )

    private val appWide: List<SourceKey<*>> = listOf(
        Sources.Session, Sources.SystemSchemeAvailable, Sources.PackLibrary, Sources.PackLaunches,
        Sources.LaunchState, Sources.PackInstalls, Sources.ConsoleVisible,
    )

    private val composedCommands: List<CommandKey<*>> = listOf(
        Commands.ApplyTheme, Commands.SetDarkTheme, Commands.SetThemeMode, Commands.SetLocale,
    )

    private val appCommands: List<CommandKey<*>> = listOf(
        Commands.SignIn, Commands.SignOut, Commands.LaunchPack, Commands.StopPack, Commands.InstallPack,
        Commands.DismissInstall, Commands.CancelInstall, Commands.UpdatePack, Commands.DeletePack, Commands.SetConsoleVisible,
    )

    @Test
    fun `every core key is made by a factory at its level and answers there with no shell drawn`() {
        val launcher = Launcher()
        val page = launcher.composition().child(ScopeLevel.Page)
        (composed + appWide).forEach { key ->
            val declared = assertNotNull(launcher.app.declarationOf(key.id), "${key.id} is not declared")
            assertEquals(KeyOrigin.Factory, declared.origin, "${key.id} is not made by a factory")
            assertEquals(key.level, declared.level)
            assertNotNull(page.find(key), "${key.id} is not read from a page below its level")
            assertNotNull(launcher.app.sampleOf(key.id), "${key.id} has no sample to be shown on")
        }
        composed.forEach { key -> assertNull(launcher.app.find(key), "${key.id} is the composition's and has no answer above it") }
        appWide.forEach { key -> assertNotNull(launcher.app.find(key), "${key.id} has no answer at the app") }
        (composedCommands + appCommands).forEach { key ->
            val declared = assertNotNull(launcher.app.declarationOf(key.id), "${key.id} is not declared")
            assertEquals(KeyOrigin.Factory, declared.origin, "${key.id} is not made by a factory")
            assertEquals(key.level, declared.level)
            assertNotNull(page.findCommand(key), "${key.id} is not fired from a page below its level")
        }
        assertEquals(listOf(pack), launcher.source(page, Sources.PackLibrary))
        assertSame(launcher.appearance.library.value, launcher.source(page, Sources.ThemeLibrary))
        assertTrue(launcher.problems.reported.isEmpty(), "nothing failed: ${launcher.problems.reported}")
    }

    @Test
    fun `a core service that cannot be made is one notice in the person's words, however many keys and windows`() {
        val launcher = Launcher(withActions = false, notified = true)
        assertNull(launcher.app.find(Sources.LaunchState))
        assertNull(launcher.app.findCommand(Commands.StopPack))
        assertNull(launcher.app.findCommand(Commands.LaunchPack))
        assertNull(launcher.app.find(Sources.PackInstalls))
        awaitTrue("the notice") { launcher.center.groups.value.isNotEmpty() }
        Thread.sleep(200)
        val group = launcher.center.groups.value.single()
        assertEquals("core:part:Packs", group.sourceKey)
        assertEquals("Packs cannot be launched or changed", group.events.single().title)
        assertTrue(group.events.none { "launch.state" in it.title || "pack.stop" in it.title }, "key ids stay in the log")
        assertEquals(1, launcher.archived.size, "one record in the history")
    }

    @Test
    fun `one cause reported from many threads at once is one notice`() {
        val archived = CopyOnWriteArrayList<PersistedNotification>()
        val center = NotificationCenter(archive = { archived += it })
        val reporter = NotifiedCoreProblems(center, scope) { stringsFor(AppLocale.ENGLISH) }
        val start = CyclicBarrier(16)
        val threads = (1..16).map { n ->
            thread {
                start.await(5, TimeUnit.SECONDS)
                reporter.report(CoreProblem.PartMissing(CorePart.Packs, IllegalStateException("broken $n")))
            }
        }
        threads.forEach { it.join(5_000) }
        awaitTrue("the notice") { center.groups.value.isNotEmpty() }
        Thread.sleep(300)
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
            assertSame(launcher.session, level.services.find<SessionStore>(), "no level below the app makes a store of its own")
        }
        assertEquals("Steve", (launcher.source(first, Sources.Session) as? AppState.Authenticated)?.session?.playerName)
        launcher.fire(first, Commands.SignIn, SessionData(playerName = "Alex"))
        assertEquals("Alex", (launcher.source(second, Sources.Session) as? AppState.Authenticated)?.session?.playerName)
        launcher.composition()
        assertEquals(1, launcher.accounts.reads.get(), "a new composition does not sign in again")
    }

    @Test
    fun `startup signs in the chosen offline name, and with nothing to sign in with leaves the launcher signed out`() {
        val named = Launcher(SettingsData(offlinePlayerName = "Steve"))
        assertEquals("Steve", (settled(named) as? AppState.Authenticated)?.session?.playerName)
        assertIs<AppState.Unauthenticated>(settled(Launcher()))
    }

    @Test
    fun `a sign-in made by hand while the startup one is in flight stays`() {
        ActionRing.clear()
        val hold = CountDownLatch(1)
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve")) { this.hold = hold }
        val store = launcher.session
        assertTrue(launcher.accounts.entered.await(5, TimeUnit.SECONDS), "the startup sign-in never reached the store")
        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        hold.countDown()
        awaitTrue("the startup sign-in standing down") {
            ActionRing.snapshot().any { it.text.contains("the manual one stays") }
        }
        assertEquals("Alex", (store.state.value as? AppState.Authenticated)?.session?.playerName)
    }

    @Test
    fun `a startup sign-in that throws leaves the launcher signed out and says so`() {
        val launcher = Launcher(SettingsData(offlinePlayerName = "Steve")) { failRead = true }
        assertIs<AppState.Unauthenticated>(settled(launcher))
        assertIs<CoreProblem.SignInFailed>(launcher.problems.reported.single())
    }

    @Test
    fun `a sign-in that works takes back the notice that one failed`() {
        val launcher = Launcher(notified = true) { failRead = true }
        settled(launcher)
        awaitTrue("the sign-in failure notice") { launcher.center.groups.value.any { it.sourceKey == "core:sign-in" } }
        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        awaitTrue("the notice taken back") { launcher.center.groups.value.none { it.sourceKey == "core:sign-in" } }
    }

    @Test
    fun `sign-out clears every account and the names at once`() {
        val launcher = Launcher(SettingsData(preferredFaceProvider = "microsoft"))
        settled(launcher)
        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Alex")
        launcher.fire(launcher.app, Commands.SignOut, Unit)
        await(launcher.session.state, "the sign-out") { it is AppState.Unauthenticated }
        assertTrue(launcher.accounts.cleared)
        assertNull(launcher.settings.current.preferredFaceProvider)
        assertNull(launcher.settings.current.offlinePlayerName)
        assertTrue(launcher.problems.reported.isEmpty())
    }

    @Test
    fun `a sign-out that cannot clear the accounts stays signed in and says so`() {
        val launcher = Launcher { failClear = true }
        settled(launcher)
        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        // The whole sign-out, finished, before anything is looked at.
        runBlocking { withTimeout(5.seconds) { launcher.session.signOut().join() } }
        assertIs<CoreProblem.SignOutFailed>(launcher.problems.reported.single())
        assertIs<AppState.Authenticated>(launcher.session.state.value, "the accounts are still there, so the session is")
    }

    @Test
    fun `a sign-out that cleared the accounts but not the offline name signs out and says the name was kept`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Alex")
        launcher.settings.failWrites = true
        runBlocking { withTimeout(5.seconds) { launcher.session.signOut().join() } }
        assertIs<AppState.Unauthenticated>(launcher.session.state.value)
        assertTrue(launcher.accounts.cleared)
        assertEquals("Alex", launcher.settings.current.offlinePlayerName, "the name really is still there")
        assertIs<CoreProblem.SignedOutNameKept>(launcher.problems.reported.single(), "it says what happened, not that sign-out failed")
    }

    @Test
    fun `the session store is closed with the app, and does nothing after`() {
        val launcher = Launcher()
        val store = launcher.session
        settled(launcher)
        launcher.application.close()
        val _ = store.signOut()
        Thread.sleep(200)
        assertFalse(launcher.accounts.cleared, "a closed store still cleared the accounts")
    }

    // ── Packs ─────────────────────────────────────────────────────────

    @Test
    fun `launch runs as the launcher's session, and offline as the chosen name when nobody is signed in`() {
        val launcher = Launcher()
        settled(launcher)

        launcher.settings.current = launcher.settings.current.copy(offlinePlayerName = "Steve")
        launcher.fire(launcher.app, Commands.LaunchPack, pack.id)
        awaitTrue("the offline launch") { launcher.actions.launched.isNotEmpty() }
        val (offline, launchedPack) = launcher.actions.launched.single()
        assertEquals("Steve", offline.playerName)
        assertTrue(offline.offline)
        assertEquals(pack, launchedPack)

        launcher.fire(launcher.app, Commands.SignIn, SessionData(playerName = "Alex"))
        launcher.fire(launcher.app, Commands.LaunchPack, pack.id)
        assertEquals("Alex", launcher.actions.launched.last().first.playerName, "a signed-in launch runs as the session, at once")
    }

    @Test
    fun `launching a pack that is not installed fails out loud`() {
        val launcher = Launcher()
        launcher.fire(launcher.app, Commands.LaunchPack, "not-installed")
        assertEquals(listOf<CoreProblem>(CoreProblem.PackNotInstalled("not-installed")), launcher.problems.reported.toList())
        assertTrue(launcher.actions.launched.isEmpty())
    }

    @Test
    fun `nobody signed in and no offline name starts nothing`() {
        val launcher = Launcher()
        settled(launcher)
        launcher.fire(launcher.app, Commands.LaunchPack, pack.id)
        Thread.sleep(200)
        assertTrue(launcher.actions.launched.isEmpty())
    }

    @Test
    fun `stop, install, update and delete reach the launcher's actions with the pack they name`() {
        val launcher = Launcher()
        val app = launcher.app
        val request = PackInstallRequest(
            CataloguePack(PackOrigin.Mirror, "industrial", "Industrial", "A pack"),
            CataloguePackVersion("v5", "5", "5"),
        )
        launcher.fire(app, Commands.StopPack, pack.id)
        launcher.fire(app, Commands.InstallPack, request)
        launcher.fire(app, Commands.DismissInstall, "k1")
        launcher.fire(app, Commands.CancelInstall, "k2")
        launcher.fire(app, Commands.UpdatePack, pack.id)
        launcher.fire(app, Commands.DeletePack, pack.id)
        launcher.fire(app, Commands.UpdatePack, "not-installed")
        launcher.fire(app, Commands.DeletePack, "not-installed")
        awaitTrue("the delete") { launcher.actions.deleted.isNotEmpty() }
        assertEquals(listOf(pack.id), launcher.actions.stopped)
        assertEquals(listOf(request), launcher.actions.installed)
        assertEquals(listOf("k1"), launcher.actions.dismissed)
        assertEquals(listOf("k2"), launcher.actions.cancelled)
        assertEquals(listOf(pack), launcher.actions.updated)
        Thread.sleep(100)
        assertEquals(listOf(pack), launcher.actions.deleted)
    }

    @Test
    fun `the launches source lists the packs in flight and drops one that ended`() {
        val launcher = Launcher()
        launcher.indications.setLaunchIndication(pack.id, LaunchIndication.Running)
        assertEquals(mapOf(pack.id to LaunchIndication.Running), launcher.source(launcher.app, Sources.PackLaunches))
        launcher.indications.setLaunchIndication(pack.id, null)
        assertEquals(emptyMap(), launcher.source(launcher.app, Sources.PackLaunches))
    }

    // ── Appearance and console ────────────────────────────────────────

    @Test
    fun `the appearance commands change the store every composition reads, and save it`() {
        val launcher = Launcher(SettingsData(isDarkTheme = true, themeMode = ThemeMode.System, locale = "en"))
        val composition = launcher.composition()
        val other = launcher.composition()
        val theme = Themes.all.last().id

        launcher.fire(composition, Commands.ApplyTheme, theme)
        assertEquals(theme, launcher.source(other, Sources.ThemeLibrary).selected, "another composition reads the same store")

        launcher.fire(composition, Commands.SetDarkTheme, false)
        assertFalse(launcher.source(other, Sources.DarkTheme))
        assertEquals(ThemeMode.Manual, launcher.source(other, Sources.ThemeMode), "a choice made by hand takes the scheme back from the system")

        launcher.fire(composition, Commands.SetThemeMode, ThemeMode.Wallpaper)
        launcher.appearance.wallpaperLuminanceChanged(0.2f)
        assertTrue(launcher.source(composition, Sources.DarkTheme), "a dark wallpaper turns the wallpaper mode dark")
        assertEquals(ThemeMode.Wallpaper, launcher.source(composition, Sources.ThemeMode))

        launcher.fire(composition, Commands.SetLocale, AppLocale.fromTag("de"))
        assertEquals("de", launcher.source(other, Sources.Locale).tag)

        launcher.appearance.setSystemSchemeAvailable(true)
        assertTrue(launcher.source(launcher.app, Sources.SystemSchemeAvailable))

        launcher.preferences.drain(5.seconds)
        val saved = launcher.settings.current
        assertTrue(saved.isDarkTheme)
        assertEquals(ThemeMode.Wallpaper, saved.themeMode)
        assertTrue(saved.themeFromWallpaper)
        assertEquals("de", saved.locale)
        assertTrue(dir.resolve("themes.json").exists())
        assertEquals(theme, ThemeManager(dir) { _, _ -> }.load().selected, "the choice is in the theme file")
    }

    @Test
    fun `turning the wallpaper mode on applies the brightness the wallpaper last reported`() {
        val launcher = Launcher(SettingsData(isDarkTheme = false, themeMode = ThemeMode.Manual))
        val composition = launcher.composition()
        launcher.appearance.wallpaperLuminanceChanged(0.2f)
        assertFalse(launcher.source(composition, Sources.DarkTheme), "the person's own choice ignores the wallpaper")
        launcher.fire(composition, Commands.SetThemeMode, ThemeMode.Wallpaper)
        assertTrue(launcher.source(composition, Sources.DarkTheme), "the dark wallpaper it already knew decides at once")
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
        var packs: List<PackInstance>? = null
        var session: AppState? = null
        var dark: Boolean? = null
        var launch: ((String) -> Unit)? = null
        var stop: ((String) -> Unit)? = null
        var show: (() -> Unit)? = null
        onEventThread {
            val scene = ImageComposeScene(100, 100, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides data,
                    LocalWidgetCommandRegistry provides commands,
                    LocalScope provides launcher.app,
                ) {
                    ChildLevel(ScopeLevel.Window) {
                        ChildLevel(ScopeLevel.Composition) {
                            packs = rememberScopedSource(Sources.PackLibrary).valueOrNull()
                            session = rememberScopedSource(Sources.Session).valueOrNull()
                            dark = rememberScopedSource(Sources.DarkTheme).valueOrNull()
                            launch = rememberScopedCommand(Commands.LaunchPack)
                            // The plain app read, which a command a factory makes answers too.
                            stop = rememberCommand(Commands.StopPack)
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
                while (session !is AppState.Authenticated && waited < 250) {
                    Thread.sleep(20)
                    Snapshot.sendApplyNotifications()
                    scene.settle(1)
                    waited++
                }
            } finally {
                scene.close()
            }
        }
        assertEquals(listOf(pack), packs)
        assertEquals("Steve", (session as? AppState.Authenticated)?.session?.playerName)
        assertEquals(true, dark)
        assertNotNull(launch).invoke(pack.id)
        assertEquals("Steve", launcher.actions.launched.single().first.playerName)
        assertNotNull(stop).invoke(pack.id)
        assertEquals(listOf(pack.id), launcher.actions.stopped)
        assertNotNull(show).invoke()
        assertTrue(launcher.console.shouldShowConsole)
    }
}
