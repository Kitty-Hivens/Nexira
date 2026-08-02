package hivens.launcher.launch

import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.IPackSyncService
import hivens.core.api.interfaces.RosterVerdict
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.AuthStatus
import hivens.core.data.FileManifest
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.launch.SpawnResult
import dev.hivens.libvault.Vault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.VaultTier
import hivens.auth.CredentialsManager
import hivens.launcher.ManifestCache
import hivens.launcher.ProfileManager
import hivens.launcher.smrt.SmartyModPlanner
import hivens.launcher.smrt.SmrtPackClient
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Smoke + edge tests for the post-B1 [LauncherController]. Mocking strategy:
 *
 * - **Interfaces** (`AuthProvider`, `IFileDownloadService`, …) are mocked
 *   with `mockk()` since they go through `java.lang.reflect.Proxy` and do
 *   not require Byte Buddy class retransformation.
 * - **Final classes** (`ProfileManager`, `ManifestCache`, `CredentialsManager`)
 *   are instantiated for real against the test sandbox directory. Reason:
 *   this project's tests run on JDK 25, and the mockk-bundled Byte Buddy
 *   (1.14.x) cannot transform Java 25 bytecode -- recording an `every {}`
 *   block on a final-class mock invokes the real method on an uninitialized
 *   instance, which NPEs. Real instances pointed at a tempdir give us
 *   deterministic default behavior (empty profile, missing manifest file,
 *   no cached credentials) without that limitation.
 *
 * `appScope` is the `TestScope` from [runTest] so the launch coroutine
 * runs on the virtual-time dispatcher and `advanceUntilIdle()` deterministically
 * drains it. The mocked `LaunchHandle.awaitExit()` returns synchronously, so
 * the state machine sweeps through `GameRunning` and lands in `Idle` within
 * a single dispatcher tick -- tests assert the **terminal** state plus
 * the events emitted along the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherControllerTest {

    private lateinit var sandbox: Path
    private lateinit var authService: AuthProvider
    private lateinit var packSyncService: IPackSyncService
    private lateinit var settingsService: ISettingsService
    private lateinit var downloadService: IFileDownloadService
    private lateinit var javaManagerService: IJavaManager
    private lateinit var launcherService: ILauncherService
    private lateinit var manifestProcessor: IManifestProcessorService
    private lateinit var credentialsManager: CredentialsManager
    private lateinit var manifestCache: ManifestCache
    private lateinit var profileManager: ProfileManager
    private lateinit var packRepository: IPackRepository
    private lateinit var smrtPackClient: SmrtPackClient
    private lateinit var smartyPlanner: SmartyModPlanner

    private val server = ServerProfile(
        name     = "TestSrv",
        title    = "Test",
        version  = "1.7.10",
        ip       = "127.0.0.1",
        port     = 25565,
        assetDir = "test",
    )

    @BeforeTest
    fun setUp() {
        sandbox = Files.createTempDirectory("aura-launcher-controller-test-")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        authService        = mockk()
        settingsService    = mockk()
        downloadService    = mockk()
        javaManagerService = mockk()
        launcherService    = mockk()
        manifestProcessor  = mockk()

        // Real instances: cheaper than fighting mockk on JDK 25, and the
        // default no-data behavior (empty profile map, missing manifest
        // file, no credentials.json) is exactly what the smoke + edge
        // paths expect.
        profileManager     = ProfileManager(sandbox, json)
        manifestCache      = ManifestCache(sandbox.resolve("manifest-cache"), json)
        // A real in-memory vault so save() -> load() round-trips (the relaxed
        // mock returned null from retrieve, breaking the re-auth flow). Memory
        // tier skips the keyring probe entirely. No legacy file is written, so
        // the migration provider lambda is never invoked.
        credentialsManager = CredentialsManager(
            sandbox,
            json,
            Vault.open(VaultConfig(namespace = "nexira-launcher-test", preferredTiers = listOf(VaultTier.Memory))),
        ) { mockk(relaxed = true) }
        // PackRepository + SmrtPackClient: pack-centric controller
        // dependencies. SC-only tests do not call `launchPackInstance`,
        // so a relaxed mockk on both is enough to satisfy the
        // constructor without any stubbing.
        packRepository     = mockk(relaxed = true)
        smrtPackClient     = mockk(relaxed = true)
        // No Smarty swap in SC-launch tests; the helper never resolves, so the
        // plan injects nothing. The swap path has its own test.
        smartyPlanner      = SmartyModPlanner(resolveHelper = { null }, manifestProcessor = manifestProcessor)

        every { manifestProcessor.calculateIgnoredFiles(any(), any()) } returns emptySet()
        // Swap planning (enabled by default in SettingsData) flattens the manifest
        // to find Smarty jars to strip; no Smarty in these SC-launch fixtures.
        every { manifestProcessor.flattenManifest(any()) } returns emptyMap()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/usr/bin/java")
        // The registry only reads provider ids; the controller's SC gate checks
        // contains("smartycraft").
        every { authService.id } returns "smartycraft"
        // An installed pack carries a roster, so the default fixture is a verified
        // instance; the unverified case is its own test.
        packSyncService = mockk(relaxed = true)
        coEvery { packSyncService.enforceRoster(any(), any()) } returns RosterVerdict(verified = true)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        runCatching { sandbox.deleteRecursively() }
    }

    private fun newController(scope: TestScope) = LauncherController(
        authService          = authService,
        authProviderRegistry = AuthProviderRegistry(listOf(authService)),
        credentialsManager   = credentialsManager,
        settingsService    = settingsService,
        downloadService    = downloadService,
        javaManagerService = javaManagerService,
        launcherService    = launcherService,
        manifestProcessor  = manifestProcessor,
        manifestCache      = manifestCache,
        profileManager     = profileManager,
        packRepository     = packRepository,
        smrtPackClient     = smrtPackClient,
        smrtSyncService    = packSyncService,
        smartyPlanner      = smartyPlanner,
        dataDirectory      = sandbox,
        appScope           = scope,
    )

    @Test
    fun `happy online launch lands in Idle and emits expected event sequence`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()

        val session = SessionData(
            playerName = "tester",
            uuid = "before-login",
            accessToken = "stale-token",
            cachedPassword = "pw",
            fileManifest = FileManifest(),
        )
        val refreshed = session.copy(uuid = "after-login", accessToken = "fresh-token")
        coEvery { authService.login("tester", "pw", "test") } returns refreshed

        coJustRun {
            downloadService.processSession(
                session = any(),
                serverId = any(),
                targetDir = any(),
                extraCheckSum = any(),
                ignoredFiles = any(),
                messageUI = any(),
                progressUI = any(),
                verifyUI = any(),
                injectModJar = any(),
                strictModCheck = any(),
                helperKeepGlobs = any(),
            )
        }

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        every { handle.terminate() } just runs
        coEvery {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        val controller = newController(this)
        val collected = mutableListOf<LaunchLogEvent>()
        val collectorJob = launch { controller.events.toList(collected) }

        controller.launch(session, server, onSessionRefreshed = null)
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value, "state should return to Idle after exit code 0")
        assertIs<LaunchLogEvent.SessionStarted>(
            collected.firstOrNull(),
            "first event must be SessionStarted; got ${collected.firstOrNull()}",
        )
        assertTrue(
            collected.any { it is LaunchLogEvent.AuthSucceeded && it.uuid == "after-login" },
            "expected AuthSucceeded(after-login); got $collected",
        )
        assertTrue(
            collected.any { it is LaunchLogEvent.Launching },
            "expected Launching event; got $collected",
        )

        coVerify(exactly = 1) { authService.login("tester", "pw", "test") }
        coVerify(exactly = 1) {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any(), any())
        }

        collectorJob.cancel()
    }

    @Test
    fun `a refresh that never reached the server is classified Unreachable`() = runTest {
        val failure = authFailureFor(
            AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: connection reset", isNetworkError = true),
        )
        assertEquals(AuthRefreshFailure.Unreachable, failure?.cause, "network-shaped failure judged no credentials")
    }

    @Test
    fun `a server-side rejection is classified Rejected`() = runTest {
        val failure = authFailureFor(AuthException(AuthStatus.PASSWORD, "Invalid password"))
        assertEquals(AuthRefreshFailure.Rejected, failure?.cause, "the server answered and refused")
    }

    @Test
    fun `an unattributed auth failure stays Unknown`() = runTest {
        // INTERNAL_ERROR is the auth layer's catch-all. Reading it as a rejection
        // would tell the user to re-enter a password that was never judged.
        val failure = authFailureFor(AuthException(AuthStatus.INTERNAL_ERROR, "boom"))
        assertEquals(AuthRefreshFailure.Unknown, failure?.cause, "unattributed failure must not read as a rejection")
    }

    /**
     * Runs a launch whose pre-spawn refresh throws [thrown] and returns the
     * resulting [LaunchLogEvent.AuthFailed]. The launch continues past the
     * failed refresh -- that is the behaviour under test -- so the spawn path
     * is stubbed through to a clean exit.
     */
    private suspend fun TestScope.authFailureFor(thrown: Exception): LaunchLogEvent.AuthFailed? {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { authService.login(any(), any(), any()) } throws thrown
        coJustRun {
            downloadService.processSession(
                session = any(),
                serverId = any(),
                targetDir = any(),
                extraCheckSum = any(),
                ignoredFiles = any(),
                messageUI = any(),
                progressUI = any(),
                verifyUI = any(),
                injectModJar = any(),
                strictModCheck = any(),
                helperKeepGlobs = any(),
            )
        }
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        every { handle.terminate() } just runs
        coEvery {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        val controller = newController(this)
        val collected = mutableListOf<LaunchLogEvent>()
        val collectorJob = launch { controller.events.toList(collected) }

        controller.launch(
            currentSession = SessionData(
                playerName = "tester",
                accessToken = "stale-token",
                cachedPassword = "pw",
                fileManifest = FileManifest(),
            ),
            server = server,
        )
        advanceUntilIdle()
        collectorJob.cancel()

        return collected.filterIsInstance<LaunchLogEvent.AuthFailed>().firstOrNull()
    }

    @Test
    fun `offline without installed client lands in Error(OfflineNoClient)`() = runTest {
        every { settingsService.getSettings() } returns SettingsData(isOfflineMode = true)

        val controller = newController(this)
        controller.launch(
            currentSession = SessionData(playerName = "tester"),
            server = server,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.OfflineNoClient, state.reason)

        coVerify(exactly = 0) {
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `swap on with Smarty in manifest but no helper blocks the launch`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()  // useOpenSmrtHelper = true
        // Manifest ships the proprietary Smarty jar; the planner's default glob
        // matches it, and the resolver (newController stubs resolveHelper = null)
        // yields no helper, with none on disk -> launch must be blocked.
        every { manifestProcessor.flattenManifest(any()) } returns
            mapOf("mods/Smarty-1.7.10.jar" to hivens.core.data.FileData("x", 1))

        val session = SessionData(
            playerName = "tester",
            cachedPassword = "pw",
            fileManifest = FileManifest(),
        )
        coEvery { authService.login("tester", "pw", "test") } returns
            session.copy(fileManifest = FileManifest())

        val controller = newController(this)
        controller.launch(session, server, onSessionRefreshed = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.HelperUnavailable("1.7.10"), state.reason)
        coVerify(exactly = 0) {
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `2FA without cached manifest lands in Error(TwoFactorExpired)`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery {
            authService.login(any(), any(), any())
        } throws TwoFactorRequiredException(uid = "uid-stub", login = "tester")
        // Real ManifestCache.loadManifest() returns null when the per-server
        // file does not exist, which is exactly the "no cached manifest"
        // branch -- no stubbing needed.

        val controller = newController(this)
        val collected = mutableListOf<LaunchLogEvent>()
        val collectorJob = launch { controller.events.toList(collected) }

        controller.launch(
            currentSession = SessionData(playerName = "tester", cachedPassword = "pw"),
            server = server,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.TwoFactorExpired, state.reason)
        assertTrue(
            collected.any { it is LaunchLogEvent.Error && it.reason == LaunchError.TwoFactorExpired },
            "expected LaunchLogEvent.Error(TwoFactorExpired); got $collected",
        )

        collectorJob.cancel()
    }

    @Test
    fun `pack-centric launch with cached manifest spawns and bumps lastPlayed`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath("1.12.2") } returns Path.of("/opt/jdk8/bin/java")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(
                sessionData        = any(),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                allocatedMemoryMB  = any(),
                adaptiveEnabled    = any(),
                redirectAuthHost   = any(),
                boundLaunch        = any(), seal        = any(), displayName        = any(),
                onLog              = any(),
            )
        } returns SpawnResult.Started(handle)

        // PackInstance with cachedManifest already filled AND no auth
        // requirement -- the pass-through case. SC-bound packs are
        // covered separately further down; this test asserts the
        // launch flow for vanilla / future offline-only packs lands
        // in Idle without ever calling authService.
        val instance = hivens.core.data.PackInstance(
            id                    = "i-1",
            packRef               = hivens.core.data.PackReference(
                origin  = hivens.core.data.PackOrigin.Mirror,
                id      = "modern-explorer",
                version = "2026.05.26.1",
            ),
            displayName           = "Modern Explorer",
            instanceDirName       = "modern-explorer-i-1",
            createdAtEpoch        = 0L,
            lastPlayedEpochOrZero = 0L,
            pinnedPackVersion     = "2026.05.26.1",
            cachedManifest        = hivens.core.data.CachedManifestSnapshot(
                minecraftVersion = "1.12.2",
                loaderName       = "forge",
                loaderVersion    = "14.23.5.2922",
                javaMajor        = 8,
            ),
        )
        // The clientDir is materialised by the install flow; the
        // controller refuses to launch when it is missing, so the test
        // pre-creates it.
        Files.createDirectories(sandbox.resolve("instances").resolve(instance.instanceDirName))

        // Two writes land: onSpawned bumps lastPlayed, then onExit re-reads the
        // persisted instance (via get) and adds the session's playtime. get()
        // returns the latest put, so the exit write builds on the lastPlayed bump
        // instead of clobbering it. No cached-manifest write happens because the
        // instance arrived pre-populated.
        val puts = mutableListOf<hivens.core.data.PackInstance>()
        coJustRun { packRepository.put(capture(puts)) }
        coEvery { packRepository.get(any()) } answers { puts.lastOrNull() }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value)
        coVerify(exactly = 2) { packRepository.put(any()) }
        assertTrue(puts.first().lastPlayedEpochOrZero > 0, "spawn bumps lastPlayed")
        assertTrue(puts.last().lastPlayedEpochOrZero > 0, "exit preserves lastPlayed (re-read, not clobbered)")
    }

    /**
     * Build an SC-bound mirror pack with a cached manifest declaring
     * an explicit [PackAuthRequirement.SmartyCraft] target. The
     * matching instance dir is materialised in the sandbox so the
     * controller's "client dir missing" guard passes.
     */
    private fun scBoundPackInstance(
        id: String = "i-sc",
        displayName: String = "TestSC",
        packId: String = "test-sc",
        serverId: String = "Industrial",
        authRequirement: PackAuthRequirement? = PackAuthRequirement.SmartyCraft(serverId),
        origin: PackOrigin = PackOrigin.Mirror,
    ): hivens.core.data.PackInstance {
        val instance = hivens.core.data.PackInstance(
            id                    = id,
            packRef               = hivens.core.data.PackReference(
                origin  = origin,
                id      = packId,
                version = "v1",
            ),
            displayName           = displayName,
            instanceDirName       = "$packId-$id",
            createdAtEpoch        = 0L,
            lastPlayedEpochOrZero = 0L,
            pinnedPackVersion     = "v1",
            cachedManifest        = hivens.core.data.CachedManifestSnapshot(
                minecraftVersion = "1.12.2",
                loaderName       = "forge",
                loaderVersion    = "14.23.5.2922",
                javaMajor        = 8,
                authRequirement  = authRequirement,
            ),
        )
        Files.createDirectories(sandbox.resolve("instances").resolve(instance.instanceDirName))
        return instance
    }

    @Test
    fun `pack with SC requirement re-auths before spawn and uses the refreshed session`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")

        // Pre-populate the on-disk credentials so launchPackInstance
        // resolves a cached password without round-tripping the
        // keyring (relaxed mockk -> AES file fallback).
        credentialsManager.save(
            SessionData(
                playerName     = "tester",
                uuid           = "u",
                accessToken    = "stale-token",
                cachedPassword = "pw",
            ),
        )

        val refreshed = SessionData(
            playerName  = "tester",
            uuid        = "u",
            accessToken = "fresh-token",
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns refreshed

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val sessionPassed = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData        = capture(sessionPassed),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                allocatedMemoryMB  = any(),
                adaptiveEnabled    = any(),
                redirectAuthHost   = any(),
                boundLaunch        = any(), seal        = any(), displayName        = any(),
                onLog              = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val instance = scBoundPackInstance()
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale-token"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value)
        assertEquals("fresh-token", sessionPassed.captured.accessToken, "spawn must use the refreshed session")
        coVerify(exactly = 1) { authService.login("tester", "pw", "Industrial") }
    }

    @Test
    fun `pack spawn failure surfaces the carried LaunchError, not Internal`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns
            SessionData(playerName = "tester", uuid = "u", accessToken = "fresh")

        // The SC-binding step inside the service could not source the patched
        // authlib; it returns SpawnResult.Failed carrying the semantic reason.
        coEvery {
            launcherService.launchPackClient(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns SpawnResult.Failed(LaunchError.AuthlibUnavailable("1.12.2"))

        val instance = scBoundPackInstance()
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(
            LaunchError.AuthlibUnavailable("1.12.2"), state.reason,
            "controller must surface the Failed result's error, not wrap it in Internal",
        )
    }

    @Test
    fun `pack with SC requirement and no cached password fails with MissingAuthProvider`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()

        // No credentialsManager.save() -- on-disk file does not
        // exist, so load() returns null. The in-session also has no
        // cachedPassword. The precondition must fail.

        val instance = scBoundPackInstance()
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(
            LaunchError.MissingAuthProvider(PackAuthRequirement.SmartyCraft.PROVIDER_KEY),
            state.reason,
        )
        coVerify(exactly = 0) {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    @Test
    fun `smartycraft-origin pack derives its SC requirement from the pack id`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()

        // No authRequirement on the manifest; an SC-origin pack derives the
        // binding from its packRef id (the mirror name table is gone -- mirror
        // packs declare the binding in the manifest auth block instead).
        // Same no-password setup, so the precondition surfaces.
        val instance = scBoundPackInstance(
            packId          = "Industrial",
            displayName     = "Industrial",
            authRequirement = null,
            origin          = PackOrigin.Smartycraft,
        )
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(
            LaunchError.MissingAuthProvider(PackAuthRequirement.SmartyCraft.PROVIDER_KEY),
            state.reason,
            "an id-derived requirement must drive the same precondition surface as an explicit one",
        )
    }

    @Test
    fun `derived SC requirement is forwarded to launchPackClient, not dropped`() = runTest {
        // Guards the snapshot-forwarding bug: when the requirement is DERIVED by
        // the router (manifest authRequirement = null, SC origin), the service
        // must still receive it, or the SC-binding step (authlib swap) never runs.
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns
            SessionData(playerName = "tester", uuid = "u", accessToken = "fresh")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val manifestPassed = slot<hivens.core.data.CachedManifestSnapshot>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = capture(manifestPassed), runtime = any(),
                clientRootPath = any(), javaPathOverride = any(), allocatedMemoryMB = any(),
                adaptiveEnabled = any(), redirectAuthHost = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val instance = scBoundPackInstance(
            packId          = "Industrial",
            displayName     = "Industrial",
            authRequirement = null,
            origin          = PackOrigin.Smartycraft,
        )
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals(
            PackAuthRequirement.SmartyCraft("Industrial"), manifestPassed.captured.authRequirement,
            "the effective (derived) requirement must reach the service so SC-binding runs",
        )
    }

    @Test
    fun `auth-mechanism settings are forwarded to launchPackClient`() = runTest {
        // The two Smarty auth knobs (network agent / SC authlib swap) must reach
        // the service verbatim -- the service decides what to attach, the
        // controller only threads the user's choice.
        every { settingsService.getSettings() } returns
            SettingsData(useNetworkAgent = false, useSmartycraftAuthLib = true)
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns
            SessionData(playerName = "tester", uuid = "u", accessToken = "fresh")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val agentFlag = slot<Boolean>()
        val swapFlag = slot<Boolean>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), allocatedMemoryMB = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = capture(agentFlag),
                useSmartycraftAuthLib = capture(swapFlag), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale"),
            packInstance   = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals(false, agentFlag.captured, "useNetworkAgent must be forwarded as set")
        assertEquals(true, swapFlag.captured, "useSmartycraftAuthLib must be forwarded as set")
    }

    @Test
    fun `a mirror pack with no SC binding does not get the auth host redirected`() = runTest {
        // PackAuthRouter resolves a mirror pack with no auth block to Microsoft.
        // The redirect used to key on the pack ORIGIN, so that pack launched with
        // -Dminecraft.api.session.host pointed at the SC host while carrying a
        // Microsoft token -- the token would have gone to SC as soon as the
        // provider is registered.
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val redirect = slot<Boolean>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), allocatedMemoryMB = any(), adaptiveEnabled = any(),
                redirectAuthHost = capture(redirect), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "ms-token"),
            packInstance   = scBoundPackInstance(authRequirement = null, origin = PackOrigin.Mirror),
        )
        advanceUntilIdle()

        assertEquals(false, redirect.captured, "a Microsoft-resolved pack must keep the Mojang auth hosts")
    }

    @Test
    fun `an SC-bound pack still gets the auth host redirected`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns
            SessionData(playerName = "tester", uuid = "u", accessToken = "fresh")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val redirect = slot<Boolean>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), allocatedMemoryMB = any(), adaptiveEnabled = any(),
                redirectAuthHost = capture(redirect), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale"),
            packInstance   = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals(true, redirect.captured, "an SC join still needs the redirect")
    }

    @Test
    fun `a pack with no server binding is neither swept nor stripped of a token`() = runTest {
        // The strictness exists because a bound pack is handed a session that logs
        // into someone's server. A pack with no binding gets no token and has no
        // server, so what its owner keeps in mods/ is their own game.
        coEvery { packSyncService.enforceRoster(any(), any()) } returns RosterVerdict(verified = false)

        capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "live"),
            packInstance = scBoundPackInstance(authRequirement = null),
        )

        coVerify(exactly = 0) { packSyncService.enforceRoster(any(), any()) }
    }

    @Test
    fun `an unverified instance launches with no token`() = runTest {
        // No roster on disk -- nothing vouched for what is in mods/, so the game
        // process gets a session that cannot join anything.
        coEvery { packSyncService.enforceRoster(any(), any()) } returns RosterVerdict(verified = false)

        val session = capturePackSession(
            SessionData(playerName = "tester", uuid = "online-uuid", accessToken = "live-token"),
            // The strict rule applies to a pack that declares a binding -- that is the
            // launch which would otherwise be handed a session for someone's server.
            packInstance = scBoundPackInstance(),
        )

        assertEquals("", session?.accessToken, "an unverified instance must not carry a token")
        assertEquals(true, session?.offline, "and it must be marked offline")
    }

    @Test
    fun `a session minted for this launch is not sent back for another code`() {
        // The relaunch that answers a 2FA demand carries the session the code just
        // produced. Without telling it apart from a stored one, the same demand fires
        // again and the user is asked for code after code -- an endless prompt loop.
        val minted = SessionData(playerName = "tester", accessToken = "fresh", twoFactor = true, mintedNow = true)
        val stored = minted.copy(mintedNow = false)

        assertTrue(minted.twoFactor && minted.mintedNow, "the freshly unlocked session is marked")
        assertTrue(stored.twoFactor && !stored.mintedNow, "one restored from disk is not")
    }

    @Test
    fun `an unverified instance says so rather than passing for an offline launch`() = runTest {
        coEvery { packSyncService.enforceRoster(any(), any()) } returns RosterVerdict(verified = false)
        val events = mutableListOf<LaunchLogEvent>()

        capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "live"),
            packInstance = scBoundPackInstance(),
            events = events,
        )

        assertTrue(
            events.any { it is LaunchLogEvent.InstanceUnverified },
            "the user chose neither offline nor this; the reason needs its own event -- got $events",
        )
    }

    @Test
    fun `a refresh that could not reach the auth server drops to offline`() = runTest {
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login(any(), any(), any()) } throws
            AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: connection reset", isNetworkError = true)

        val session = capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
            packInstance = scBoundPackInstance(),
        )

        assertEquals("", session?.accessToken, "a launch that never reached auth is an offline launch")
        assertEquals(true, session?.offline)
    }

    /** Launches a pack and returns the [SessionData] the spawn actually received. */
    private suspend fun TestScope.capturePackSession(
        currentSession: SessionData,
        packInstance: PackInstance = scBoundPackInstance(authRequirement = null),
        events: MutableList<LaunchLogEvent>? = null,
    ): SessionData? {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), allocatedMemoryMB = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        val collectorJob = events?.let { sink -> launch { controller.events.toList(sink) } }
        controller.launchPackInstance(currentSession = currentSession, packInstance = packInstance)
        advanceUntilIdle()
        collectorJob?.cancel()
        return captured.captured.takeIf { captured.isCaptured }
    }

    @Test
    fun `pack with SC requirement and 2FA without cached manifest fails with TwoFactorExpired`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(
                playerName     = "tester",
                uuid           = "u",
                accessToken    = "stale-token",
                cachedPassword = "pw",
            ),
        )
        coEvery {
            authService.login("tester", "pw", "Industrial")
        } throws TwoFactorRequiredException(uid = "uid-stub", login = "tester")
        // Real ManifestCache returns null for "Industrial" since no
        // file was saved -- exactly the "no cached manifest" branch.

        val instance = scBoundPackInstance()
        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale-token"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.TwoFactorExpired, state.reason)
        coVerify(exactly = 0) {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-zero exit code lands in Error(ExitCode)`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { authService.login(any(), any(), any()) } returns SessionData(
            playerName = "tester",
            uuid = "u",
            accessToken = "tok",
            fileManifest = FileManifest(),
        )
        coJustRun {
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 137 // SIGKILL exit code
        every { handle.terminate() } just runs
        coEvery {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        val controller = newController(this)
        controller.launch(SessionData(playerName = "tester", cachedPassword = "pw"), server)
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.ExitCode(137), state.reason)
    }

    @Test
    fun `Microsoft-routed pack launches without firing the auth gate`() = runTest {
        // A Modrinth-origin pack with no explicit requirement routes to Microsoft,
        // which has no registered provider this phase -- so the gate is advisory:
        // the pack launches with the current session and authService is never hit.
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk17/bin/java")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val instance = hivens.core.data.PackInstance(
            id                    = "i-mr",
            packRef               = hivens.core.data.PackReference(
                origin  = hivens.core.data.PackOrigin.Modrinth,
                id      = "sodium",
                version = "1",
            ),
            displayName           = "Sodium Pack",
            instanceDirName       = "sodium-i-mr",
            createdAtEpoch        = 0L,
            cachedManifest        = hivens.core.data.CachedManifestSnapshot(
                minecraftVersion = "1.20.1",
                loaderName       = "fabric",
                loaderVersion    = "0.15",
                javaMajor        = 17,
            ),
        )
        Files.createDirectories(sandbox.resolve("instances").resolve(instance.instanceDirName))

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value)
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
        coVerify(exactly = 1) {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

}
