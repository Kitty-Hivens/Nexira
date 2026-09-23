package hivens.launcher.launch

import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.IPackSyncService
import hivens.core.api.interfaces.RosterVerdict
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.AuthStatus
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.launch.SpawnResult
import dev.hivens.libvault.Vault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.VaultTier
import hivens.auth.CredentialsManager
import hivens.launcher.smrt.SmrtPackClient
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke + edge tests for the post-B1 [LauncherController]. Mocking strategy:
 *
 * - **Interfaces** (`AuthProvider`, `ILauncherService`, …) are mocked
 *   with `mockk()` since they go through `java.lang.reflect.Proxy` and do
 *   not require Byte Buddy class retransformation.
 * - **Final classes** (`CredentialsManager`) are instantiated for real against
 *   the test sandbox directory. Reason:
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
    private lateinit var javaManagerService: IJavaManager
    private lateinit var launcherService: ILauncherService
    private lateinit var credentialsManager: CredentialsManager
    private lateinit var packRepository: IPackRepository
    private lateinit var smrtPackClient: SmrtPackClient
    private val work = InstanceWorkRegistry()

    @BeforeTest
    fun setUp() {
        sandbox = Files.createTempDirectory("aura-launcher-controller-test-")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        authService        = mockk()
        settingsService    = mockk()
        javaManagerService = mockk()
        launcherService    = mockk()

        // A real in-memory vault so save() -> load() round-trips (the relaxed
        // mock returned null from retrieve, breaking the re-auth flow). Memory
        // tier skips the keyring probe entirely. No legacy file is written, so
        // the migration provider lambda is never invoked.
        credentialsManager = CredentialsManager(
            sandbox,
            json,
            Vault.open(VaultConfig(namespace = "nexira-launcher-test", preferredTiers = listOf(VaultTier.Memory))),
        ) { mockk(relaxed = true) }
        packRepository     = mockk(relaxed = true)
        // The interface's own read-then-write, so a test that stubs get and captures
        // put sees an update the way the production default performs it.
        coEvery { packRepository.update(any(), any()) } coAnswers {
            val transform = secondArg<(PackInstance) -> PackInstance>()
            packRepository.get(firstArg())?.let { current -> transform(current).also { packRepository.put(it) } }
        }
        smrtPackClient     = mockk(relaxed = true)

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

    private fun newController(
        scope: TestScope,
        registry: AuthProviderRegistry = AuthProviderRegistry(listOf(authService)),
    ) = LauncherController(
        authService          = authService,
        authProviderRegistry = registry,
        credentialsManager   = credentialsManager,
        settingsService    = settingsService,
        launcherService    = launcherService,
        packRepository     = packRepository,
        smrtPackClient     = smrtPackClient,
        smrtSyncService    = packSyncService,
        dataDirectory      = sandbox,
        appScope           = scope,
        work               = work,
    )

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
     * Runs an SC-bound pack launch whose pre-spawn refresh throws [thrown] and
     * returns the resulting [LaunchLogEvent.AuthFailed]. The launch continues
     * past the failed refresh -- that is the behaviour under test -- so the
     * spawn path is stubbed through to a clean exit.
     */
    private suspend fun TestScope.authFailureFor(thrown: Exception): LaunchLogEvent.AuthFailed? {
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )
        coEvery { authService.login(any(), any(), any()) } throws thrown
        val events = mutableListOf<LaunchLogEvent>()
        capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
            packInstance = scBoundPackInstance(),
            events = events,
        )
        return events.filterIsInstance<LaunchLogEvent.AuthFailed>().firstOrNull()
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
        coEvery { packRepository.get(any()) } answers { puts.lastOrNull() ?: instance }

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
     * The settings surfaces warn before rewriting the files of a pack that is
     * playing, so they need to know which pack that is. [LaunchState] does not say
     * -- it is the frontend-agnostic contract and carries no target identity.
     */
    @Test
    fun `the running pack is named while it plays and forgotten once it exits`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath("1.12.2") } returns Path.of("/opt/jdk8/bin/java")

        lateinit var controller: LauncherController
        var namedDuringSession: String? = null
        val handle = mockk<LaunchHandle>()
        // The one moment the process is live: the flow is parked in the wait.
        coEvery { handle.awaitExit() } answers {
            namedDuringSession = controller.runningPackInstanceId.value
            0
        }
        coEvery {
            launcherService.launchPackClient(
                sessionData        = any(),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                adaptiveEnabled    = any(),
                redirectAuthHost   = any(),
                boundLaunch        = any(), seal        = any(), displayName        = any(),
                onLog              = any(),
            )
        } returns SpawnResult.Started(handle)

        val instance = hivens.core.data.PackInstance(
            id                    = "i-running",
            packRef               = hivens.core.data.PackReference(
                origin  = hivens.core.data.PackOrigin.Mirror,
                id      = "modern-explorer",
                version = "2026.05.26.1",
            ),
            displayName           = "Modern Explorer",
            instanceDirName       = "modern-explorer-i-running",
            createdAtEpoch        = 0L,
            pinnedPackVersion     = "2026.05.26.1",
            cachedManifest        = hivens.core.data.CachedManifestSnapshot(
                minecraftVersion = "1.12.2",
                loaderName       = "forge",
                loaderVersion    = "14.23.5.2922",
                javaMajor        = 8,
            ),
        )
        Files.createDirectories(sandbox.resolve("instances").resolve(instance.instanceDirName))
        coEvery { packRepository.get(any()) } returns instance

        controller = newController(this)
        assertNull(controller.runningPackInstanceId.value, "nothing is playing before the launch")

        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals("i-running", namedDuringSession)
        assertNull(controller.runningPackInstanceId.value, "and nothing is playing once the process is gone")
    }

    private fun packInstance(id: String) = hivens.core.data.PackInstance(
        id                    = id,
        packRef               = hivens.core.data.PackReference(
            origin  = hivens.core.data.PackOrigin.Mirror,
            id      = "modern-explorer",
            version = "2026.05.26.1",
        ),
        displayName           = "Pack $id",
        instanceDirName       = "dir-$id",
        createdAtEpoch        = 0L,
        pinnedPackVersion     = "2026.05.26.1",
        cachedManifest        = hivens.core.data.CachedManifestSnapshot(
            minecraftVersion = "1.12.2",
            loaderName       = "forge",
            loaderVersion    = "14.23.5.2922",
            javaMajor        = 8,
        ),
    )

    /** Launches [pack] with the service reporting [resolved] as the loader version it used. */
    private suspend fun TestScope.launchResolving(pack: PackInstance, resolved: String): List<PackInstance> {
        every { settingsService.getSettings() } returns SettingsData()
        Files.createDirectories(sandbox.resolve("instances").resolve(pack.instanceDirName))
        val puts = mutableListOf<PackInstance>()
        coJustRun { packRepository.put(capture(puts)) }
        coEvery { packRepository.get(pack.id) } answers { puts.lastOrNull() ?: pack }
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle, resolvedLoaderVersion = resolved)
        newController(this).launchPackInstance(SessionData(playerName = "tester", uuid = "u", accessToken = "tok"), pack)
        advanceUntilIdle()
        return puts
    }

    /**
     * A blank version was "the latest" asked again on every launch: it moved under the
     * pack, reran a Forge installer at each new promotion, and needed the network.
     */
    @Test
    fun `a pack that named no loader version is pinned to the one its launch resolved`() = runTest {
        val blank = packInstance("i-blank").let { it.copy(cachedManifest = it.cachedManifest!!.copy(loaderName = "fabric", loaderVersion = "")) }

        val puts = launchResolving(blank, resolved = "0.16.14")

        assertEquals("0.16.14", puts.last().cachedManifest?.loaderVersion)
    }

    /** Legacy Forge substitutes a published build for one that never was; the pack keeps what it names. */
    @Test
    fun `a named loader version is left as the pack names it`() = runTest {
        val named = packInstance("i-named")

        val puts = launchResolving(named, resolved = "14.23.5.2864")

        assertTrue(puts.none { it.cachedManifest?.loaderVersion == "14.23.5.2864" }, "got ${puts.map { it.cachedManifest?.loaderVersion }}")
    }

    /**
     * A toggle owns one field and must write only that one.
     *
     * The record the Content tab holds was captured when it rendered. An apply
     * committing in between moves the pinned version and both manifests, and writing
     * the captured copy back whole put all three back to the build the update had
     * just left, so a checkbox silently undid an update.
     */
    @Test
    fun `an optional-content flip does not write back the build the update just left`() = runTest {
        val stale = packInstance("i-toggle")
        // What the registry holds by the time the flip lands: the apply has moved on.
        val applied = stale.copy(
            packRef = stale.packRef.copy(version = "2026.06.01.1"),
            pinnedPackVersion = "2026.06.01.1",
        )
        val puts = mutableListOf<hivens.core.data.PackInstance>()
        coEvery { packRepository.get("i-toggle") } returns applied
        coJustRun { packRepository.put(capture(puts)) }

        val controller = newController(this)
        val returned = controller.setOptionalMods(
            instance = stale,
            manifest = hivens.core.api.dto.smrt.SmrtPackManifest(
                schemaVersion = 2,
                packId        = "modern-explorer",
                packVersion   = "2026.06.01.1",
                generatedAt   = "2026-06-01T00:00:00Z",
                minecraft     = hivens.core.api.dto.smrt.SmrtMinecraft("1.12.2"),
                loader        = hivens.core.api.dto.smrt.SmrtLoader("forge", "14.23.5.2922"),
                java          = hivens.core.api.dto.smrt.SmrtJava(8),
            ),
            toggles = listOf(hivens.core.data.ContentToggle("modrinth:abc", enabled = false)),
        )
        advanceUntilIdle()

        val written = puts.single()
        assertEquals("2026.06.01.1", written.pinnedPackVersion, "the flip must not roll the pin back")
        assertEquals("2026.06.01.1", written.packRef.version, "nor the reference it was applied to")
        assertEquals(
            listOf(hivens.core.data.ContentToggle("modrinth:abc", enabled = false)),
            written.optionalContent,
            "and the field the toggle does own is the one that changed",
        )
        assertEquals("2026.06.01.1", returned.pinnedPackVersion, "the caller adopts the record as written")
    }

    /**
     * The spawn wrote back the record captured before the click. Preparing a launch
     * takes a sign-in and possibly a catch-up repair, and an update committed in that
     * time was quietly reverted: pin, baseline and cached manifest back to the build
     * the update had just left.
     */
    @Test
    fun `the spawn does not write back the build an update just left`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val clicked = packInstance("i-moved")
        Files.createDirectories(sandbox.resolve("instances").resolve(clicked.instanceDirName))
        val committed = clicked.copy(pinnedPackVersion = "2026.06.01.1")
        coEvery { packRepository.get("i-moved") } returns committed
        val puts = mutableListOf<PackInstance>()
        coJustRun { packRepository.put(capture(puts)) }
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        newController(this).launchPackInstance(SessionData(playerName = "tester", uuid = "u", accessToken = "tok"), clicked)
        advanceUntilIdle()

        val stamped = puts.first()
        assertEquals("2026.06.01.1", stamped.pinnedPackVersion, "the committed build stays")
        assertTrue(stamped.lastPlayedEpochOrZero > 0, "and the one field the spawn owns is written")
    }

    /**
     * A game told to stop has up to the termination grace left, and until it goes it
     * is still writing its instance. Stop used to reopen Play at once, so a second
     * launch could start beside it, and it cancelled the launch, which skipped the
     * tail that records the session: a stopped game left no playtime at all.
     */
    @Test
    fun `a stopped game holds the launcher until it exits, and its session is recorded`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")

        val first = packInstance("first")
        val second = packInstance("second")
        for (i in listOf(first, second)) {
            Files.createDirectories(sandbox.resolve("instances").resolve(i.instanceDirName))
        }
        coEvery { packRepository.get(any()) } answers { if (firstArg<String>() == "first") first else second }
        val puts = mutableListOf<PackInstance>()
        coJustRun { packRepository.put(capture(puts)) }

        // Uninterruptible, the way `Process.waitFor` is.
        val firstExit = CompletableDeferred<Int>()
        val firstHandle = mockk<LaunchHandle>(relaxed = true)
        coEvery { firstHandle.awaitExit() } coAnswers { withContext(NonCancellable) { firstExit.await() } }
        val secondHandle = mockk<LaunchHandle>(relaxed = true)
        coEvery { secondHandle.awaitExit() } returns 0

        val handles = ArrayDeque(listOf(firstHandle, secondHandle))
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), boundLaunch = any(), seal = any(), displayName = any(),
                onLog = any(),
            )
        } answers { SpawnResult.Started(handles.removeFirst()) }

        val controller = newController(this)
        val session = SessionData(playerName = "tester", uuid = "u", accessToken = "tok")

        controller.launchPackInstance(session, first)
        advanceUntilIdle()
        assertEquals("first", controller.runningPackInstanceId.value)

        controller.abort()
        advanceUntilIdle()
        assertIs<LaunchState.Stopping>(controller.state.value, "stopping, not idle, while the process is alive")
        coVerify(exactly = 1) { firstHandle.terminate() }
        assertEquals(false, controller.launchPackInstance(session, second), "nothing starts beside a game still going")
        assertEquals("first", controller.runningPackInstanceId.value, "its files are still in use")

        firstExit.complete(143)
        advanceUntilIdle()
        assertEquals(LaunchState.Idle, controller.state.value, "a stop is not a crash")
        assertNull(controller.runningPackInstanceId.value)
        assertTrue(puts.any { it.id == "first" && it.lastPlayedEpochOrZero == 0L }, "the exit wrote the session's playtime")

        assertTrue(controller.launchPackInstance(session, second), "and the launcher is free again")
        advanceUntilIdle()
    }

    /** A pack's own Stop names that pack; a control left over from an ended launch must not end another game. */
    @Test
    fun `stopping one pack does nothing to another pack's game`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val playing = packInstance("playing")
        Files.createDirectories(sandbox.resolve("instances").resolve(playing.instanceDirName))
        coEvery { packRepository.get(any()) } returns playing
        coJustRun { packRepository.put(any()) }
        val exit = CompletableDeferred<Int>()
        val handle = mockk<LaunchHandle>(relaxed = true)
        coEvery { handle.awaitExit() } coAnswers { withContext(NonCancellable) { exit.await() } }
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        val controller = newController(this)
        controller.launchPackInstance(SessionData(playerName = "tester", uuid = "u", accessToken = "tok"), playing)
        advanceUntilIdle()

        controller.abort("some-other-pack")
        advanceUntilIdle()

        assertIs<LaunchState.GameRunning>(controller.state.value)
        coVerify(exactly = 0) { handle.terminate() }
        exit.complete(0)
        advanceUntilIdle()
    }

    /**
     * Quitting the launcher and leaving the game running never reaches the launch's
     * own tail, which was the only writer of playtime, so the session went unrecorded.
     */
    @Test
    fun `a session left running on quit is recorded before the launcher goes`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val playing = packInstance("left")
        Files.createDirectories(sandbox.resolve("instances").resolve(playing.instanceDirName))
        coEvery { packRepository.get(any()) } returns playing
        val puts = mutableListOf<PackInstance>()
        coJustRun { packRepository.put(capture(puts)) }
        val exit = CompletableDeferred<Int>()
        val handle = mockk<LaunchHandle>(relaxed = true)
        coEvery { handle.awaitExit() } coAnswers { withContext(NonCancellable) { exit.await() } }
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SpawnResult.Started(handle)

        val controller = newController(this)
        controller.launchPackInstance(SessionData(playerName = "tester", uuid = "u", accessToken = "tok"), playing)
        advanceUntilIdle()
        val beforeQuit = puts.size

        controller.settleSessionForQuit()

        assertEquals(beforeQuit + 1, puts.size, "the playtime so far is written before the process goes")
        coVerify(exactly = 0) { handle.terminate() }
        exit.complete(0)
        advanceUntilIdle()
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

    /**
     * The shape a launcher update leaves behind: a version that could not install
     * some of the pack's entries is replaced by one that can, and what it skipped is
     * still what sits in `mods/`. The roster check reads that as the instance not
     * being the pack, which is true, and used to answer by dropping the token and
     * saying nothing, over files the launcher could simply fetch.
     */
    @Test
    fun `an instance behind its pack is brought in line instead of launching without a token`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale-token", cachedPassword = "pw"),
        )
        coEvery { authService.login("tester", "pw", "Industrial") } returns
            SessionData(playerName = "tester", uuid = "u", accessToken = "fresh-token")

        // Behind the pack at the gate, in line once the missing files are fetched.
        coEvery { packSyncService.enforceRoster(any(), any()) } returnsMany listOf(
            hivens.core.api.interfaces.RosterVerdict(verified = false, mismatched = listOf("Botania.jar")),
            hivens.core.api.interfaces.RosterVerdict(verified = true),
        )
        coEvery { smrtPackClient.fetchManifestVersion("test-sc", "v1") } returns hivens.core.api.dto.smrt.SmrtPackManifest(
            schemaVersion = 2,
            packId        = "test-sc",
            packVersion   = "v1",
            generatedAt   = "2026-09-15T00:00:00Z",
            minecraft     = hivens.core.api.dto.smrt.SmrtMinecraft("1.12.2"),
            loader        = hivens.core.api.dto.smrt.SmrtLoader("forge", "14.23.5.2922"),
            java          = hivens.core.api.dto.smrt.SmrtJava(8),
        )
        coEvery { packSyncService.verifyAndRepair(any(), any(), any(), any()) } returns hivens.core.net.RepairReport(
            checked = 1, intact = 0, repaired = listOf("Botania.jar"), bytesFetched = 100L, failed = emptyMap(),
        )

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val sessionPassed = slot<SessionData>()
        val boundPassed = slot<Boolean>()
        coEvery {
            launcherService.launchPackClient(
                sessionData        = capture(sessionPassed),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                adaptiveEnabled    = any(),
                redirectAuthHost   = any(),
                boundLaunch        = capture(boundPassed), seal = any(), displayName = any(),
                onLog              = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale-token"),
            packInstance   = scBoundPackInstance(),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { packSyncService.verifyAndRepair(any(), any(), any(), any()) }
        coVerify(exactly = 2) { packSyncService.enforceRoster(any(), any()) }
        assertEquals(
            "fresh-token",
            sessionPassed.captured.accessToken,
            "once the instance matches the pack again the launch keeps its session",
        )
        assertTrue(boundPassed.captured, "and it is still the bound launch it always was")
    }

    /** A mirror that cannot be reached is not a reason to refuse a launch that was already going to be refused. */
    @Test
    fun `a repair that cannot reach the mirror leaves the launch unverified rather than failing it`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        coEvery { packSyncService.enforceRoster(any(), any()) } returns
            hivens.core.api.interfaces.RosterVerdict(verified = false, mismatched = listOf("Botania.jar"))
        coEvery { smrtPackClient.fetchManifestVersion(any(), any()) } throws java.io.IOException("offline")

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
                adaptiveEnabled    = any(),
                redirectAuthHost   = any(),
                boundLaunch        = any(), seal = any(), displayName = any(),
                onLog              = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "stale-token"),
            packInstance   = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value, "the launch still happens")
        assertEquals("", sessionPassed.captured.accessToken, "and it happens without a token, as before")
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
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
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    /**
     * An SC-origin record used to derive a binding from its pack id. That binding
     * reached the auth step and the redirect, while every guard a bound launch runs
     * under read the manifest and stayed off: a live session for an unchecked game.
     */
    @Test
    fun `an smartycraft-origin record with no auth block launches unbound and offline`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        credentialsManager.save(
            SessionData(playerName = "tester", uuid = "u", accessToken = "stale", cachedPassword = "pw"),
        )

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val sessionPassed = slot<SessionData>()
        val manifestPassed = slot<hivens.core.data.CachedManifestSnapshot>()
        val redirect = slot<Boolean>()
        val bound = slot<Boolean>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(sessionPassed), manifest = capture(manifestPassed), runtime = any(),
                clientRootPath = any(), javaPathOverride = any(),
                adaptiveEnabled = any(), redirectAuthHost = capture(redirect), boundLaunch = capture(bound),
                seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "live"),
            packInstance   = scBoundPackInstance(
                packId          = "Industrial",
                displayName     = "Industrial",
                authRequirement = null,
                origin          = PackOrigin.Smartycraft,
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
        assertNull(manifestPassed.captured.authRequirement, "no binding reaches the service")
        assertEquals(false, redirect.captured, "and nothing is redirected to the SC host")
        assertEquals(false, bound.captured)
        assertEquals("", sessionPassed.captured.accessToken, "so the game gets no token")
        assertEquals(true, sessionPassed.captured.offline)
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
                javaPathOverride = any(), adaptiveEnabled = any(),
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
                javaPathOverride = any(), adaptiveEnabled = any(),
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
                javaPathOverride = any(), adaptiveEnabled = any(),
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
    fun `a pack with no server binding is not swept, and gets no token`() = runTest {
        // The strictness exists because a bound pack is handed a session that logs
        // into someone's server. A pack with no binding has no server, so what its
        // owner keeps in mods/ is their own game. For the same reason it is not
        // handed the session in hand, which would let that game use it.
        coEvery { packSyncService.enforceRoster(any(), any()) } returns RosterVerdict(verified = false)
        val events = mutableListOf<LaunchLogEvent>()

        val session = capturePackSession(
            SessionData(playerName = "tester", uuid = "online-uuid", accessToken = "live-sc-token"),
            packInstance = scBoundPackInstance(authRequirement = null),
            events = events,
        )

        coVerify(exactly = 0) { packSyncService.enforceRoster(any(), any()) }
        assertEquals("", session?.accessToken, "the SmartyCraft token in hand must not reach an unbound game")
        assertEquals(true, session?.offline)
        assertTrue(events.any { it is LaunchLogEvent.UnboundOffline }, "and the console says why, got $events")
    }

    /** The one token an unbound launch may carry is the player's own licence. */
    @Test
    fun `an unbound pack carries the Microsoft session when that provider is signed in`() = runTest {
        val msa = mockk<AuthProvider>()
        every { msa.id } returns PackAuthRequirement.Microsoft.PROVIDER_KEY
        credentialsManager.saveAccount(
            SessionData(playerName = "licensed", uuid = "ms-u", accessToken = "ms-token", refreshToken = "r"),
            PackAuthRequirement.Microsoft.PROVIDER_KEY,
        )
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk17/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this, AuthProviderRegistry(listOf(authService, msa)))
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "live-sc-token"),
            packInstance   = scBoundPackInstance(authRequirement = null),
        )
        advanceUntilIdle()

        assertEquals("ms-token", captured.captured.accessToken)
        assertEquals("licensed", captured.captured.playerName)
    }

    /**
     * Signed in to Microsoft or not, an unbound pack launches. The derived Microsoft
     * requirement used to fail such a launch with MissingAuthProvider once the
     * provider was registered, for a pack that had never asked for an account.
     */
    @Test
    fun `an unbound pack is not refused when Microsoft is registered with no account`() = runTest {
        val msa = mockk<AuthProvider>()
        every { msa.id } returns PackAuthRequirement.Microsoft.PROVIDER_KEY
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk17/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this, AuthProviderRegistry(listOf(authService, msa)))
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "live-sc-token"),
            packInstance   = scBoundPackInstance(authRequirement = null),
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value, "the launch goes ahead")
        assertEquals("", captured.captured.accessToken, "offline, since there is no licence to carry")
    }

    /**
     * What a held jar kept an earlier switch or update from doing is carried out
     * here, and before the roster is read: the check has to see the instance as the
     * player's choice left it, not with a switched-off mod still loading.
     */
    @Test
    fun `a launch settles what a held file left owing before it checks the roster`() = runTest {
        capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "live-token"),
            packInstance = scBoundPackInstance(),
        )

        coVerifyOrder {
            packSyncService.settlePending(any())
            packSyncService.enforceRoster(any(), any())
        }
    }

    /** An unbound pack is not swept, and its owner's switches still take effect. */
    @Test
    fun `an unbound launch settles what a held file left owing too`() = runTest {
        capturePackSession(
            SessionData(playerName = "tester", uuid = "u", accessToken = "live-token"),
            packInstance = scBoundPackInstance(authRequirement = null),
        )

        coVerify(exactly = 1) { packSyncService.settlePending(any()) }
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
    fun `with reuse-session on, an SC launch carries the token in hand and does not re-login`() = runTest {
        every { settingsService.getSettings() } returns SettingsData(experimentalReuseSession = true)
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(
                playerName = "tester", uuid = "u", accessToken = "live", status = AuthStatus.OK,
            ),
            packInstance = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals("live", captured.captured.accessToken, "the token in hand is carried, not a re-minted one")
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    @Test
    fun `with reuse-session on, a session restored from the store (status null) is reused`() = runTest {
        // The regression that shipped: a session loaded after a restart carries
        // status = null, and requiring status == OK made the pack launch demand a
        // code even though auto-login had just signed in without one. A token is
        // the signal, not the status.
        every { settingsService.getSettings() } returns SettingsData(experimentalReuseSession = true)
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            // status = null and twoFactor = true, mintedNow = false: exactly what
            // CredentialsManager.loadSession produces for a restored 2FA account.
            currentSession = SessionData(
                playerName = "tester", uuid = "u", accessToken = "restored", status = null,
                twoFactor = true, mintedNow = false,
            ),
            packInstance = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals("restored", captured.captured.accessToken, "the restored token is carried, no code demanded")
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    @Test
    fun `with reuse-session on, a two-factor account is not sent back for a code`() = runTest {
        // The whole point: a 2FA session that is not minted-now would normally hit
        // TwoFactorExpired and demand a code at pack launch. With reuse on, the saved
        // token is trusted and the launch proceeds -- one code at sign-in, not per launch.
        every { settingsService.getSettings() } returns SettingsData(experimentalReuseSession = true)
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        val captured = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData = capture(captured), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(
                playerName = "tester", uuid = "u", accessToken = "live", status = AuthStatus.OK,
                twoFactor = true, mintedNow = false,
            ),
            packInstance = scBoundPackInstance(),
        )
        advanceUntilIdle()

        assertEquals("live", captured.captured.accessToken, "the confirmed 2FA token is carried, no fresh code")
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
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
                javaPathOverride = any(), adaptiveEnabled = any(),
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
        return if (captured.isCaptured) captured.captured else null
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
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-zero exit code lands in Error(ExitCode)`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 137 // SIGKILL exit code
        every { handle.terminate() } just runs
        coEvery {
            launcherService.launchPackClient(
                sessionData = any(), manifest = any(), runtime = any(), clientRootPath = any(),
                javaPathOverride = any(), adaptiveEnabled = any(),
                redirectAuthHost = any(), useNetworkAgent = any(),
                useSmartycraftAuthLib = any(), boundLaunch = any(), seal = any(), displayName = any(), onLog = any(),
            )
        } returns SpawnResult.Started(handle)
        coJustRun { packRepository.put(any()) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = scBoundPackInstance(authRequirement = null),
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.ExitCode(137), state.reason)
    }

    /**
     * The launch controls already refuse this, but a launch can also arrive from a
     * notification's relaunch or the second-factor retry. Started over an update, the
     * game read a mix of two builds.
     */
    @Test
    fun `a pack whose files are being rewritten is refused, and says why`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        val release = CompletableDeferred<Unit>()
        launch { work.during("i-sc", InstanceWork.Update) { release.await() } }
        advanceUntilIdle()

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = scBoundPackInstance(authRequirement = null),
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.InstanceBusy(InstanceWork.Update), state.reason)
        assertNull(controller.runningPackInstanceId.value, "a refused launch holds nothing")
        coVerify(exactly = 0) {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        release.complete(Unit)
    }

    /**
     * Preparing a launch already reads the instance, so it is in use from the moment
     * the launch is accepted. Named only once the game spawned, an update started in
     * between swapped files under the check that had just vouched for them.
     */
    @Test
    fun `the pack is named as in use while its launch is still preparing`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk8/bin/java")
        val spawnMay = CompletableDeferred<Unit>()
        lateinit var controller: LauncherController
        var namedBeforeSpawn: String? = null
        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            namedBeforeSpawn = controller.runningPackInstanceId.value
            spawnMay.await()
            SpawnResult.Started(handle)
        }
        coJustRun { packRepository.put(any()) }

        controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = scBoundPackInstance(authRequirement = null),
        )
        assertEquals("i-sc", controller.runningPackInstanceId.value, "claimed as soon as the launch is accepted")
        advanceUntilIdle()
        assertEquals("i-sc", namedBeforeSpawn)

        spawnMay.complete(Unit)
        advanceUntilIdle()
        assertNull(controller.runningPackInstanceId.value)
    }

    @Test
    fun `Microsoft-routed pack launches without firing the auth gate`() = runTest {
        // A Modrinth-origin pack with no explicit requirement routes to Microsoft,
        // which has no registered provider this phase -- so the gate is advisory:
        // the pack launches offline and authService is never hit.
        every { settingsService.getSettings() } returns SettingsData()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/opt/jdk17/bin/java")

        val handle = mockk<LaunchHandle>()
        coEvery { handle.awaitExit() } returns 0
        coEvery {
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

}
