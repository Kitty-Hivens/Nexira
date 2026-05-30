package hivens.launcher.launch

import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileManifest
import hivens.core.data.PackAuthRequirement
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.security.IKeyringStorage
import hivens.launcher.CredentialsManager
import hivens.launcher.ManifestCache
import hivens.launcher.ProfileManager
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
 * - **Interfaces** (`IAuthService`, `IFileDownloadService`, …) are mocked
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
 * drains it. The mocked `Process.waitFor()` returns synchronously, so the
 * state machine sweeps through `GameRunning` and lands in `Idle` within
 * a single dispatcher tick -- tests assert the **terminal** state plus
 * the events emitted along the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherControllerTest {

    private lateinit var sandbox: Path
    private lateinit var authService: IAuthService
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
        credentialsManager = CredentialsManager(sandbox, json, mockk<IKeyringStorage>(relaxed = true))
        // PackRepository + SmrtPackClient: pack-centric controller
        // dependencies. SC-only tests do not call `launchPackInstance`,
        // so a relaxed mockk on both is enough to satisfy the
        // constructor without any stubbing.
        packRepository     = mockk(relaxed = true)
        smrtPackClient     = mockk(relaxed = true)

        every { manifestProcessor.calculateIgnoredFiles(any(), any()) } returns emptySet()
        coEvery { javaManagerService.getJavaPath(any()) } returns Path.of("/usr/bin/java")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        runCatching { sandbox.deleteRecursively() }
    }

    private fun newController(scope: TestScope) = LauncherController(
        authService        = authService,
        credentialsManager = credentialsManager,
        settingsService    = settingsService,
        downloadService    = downloadService,
        javaManagerService = javaManagerService,
        launcherService    = launcherService,
        manifestProcessor  = manifestProcessor,
        manifestCache      = manifestCache,
        profileManager     = profileManager,
        packRepository     = packRepository,
        smrtPackClient     = smrtPackClient,
        smrtSyncService    = mockk(relaxed = true),
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
            )
        }

        val process = mockk<Process>()
        every { process.waitFor() } returns 0
        every { process.destroy() } just runs
        coEvery {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any())
        } returns process

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
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any())
        }

        collectorJob.cancel()
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
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any(), any())
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

        val process = mockk<Process>()
        every { process.waitFor() } returns 0
        coEvery {
            launcherService.launchPackClient(
                sessionData        = any(),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                allocatedMemoryMB  = any(),
                displayName        = any(),
                onLog              = any(),
            )
        } returns process

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

        val captured = slot<hivens.core.data.PackInstance>()
        coJustRun { packRepository.put(capture(captured)) }

        val controller = newController(this)
        controller.launchPackInstance(
            currentSession = SessionData(playerName = "tester", uuid = "u", accessToken = "tok"),
            packInstance   = instance,
        )
        advanceUntilIdle()

        assertEquals(LaunchState.Idle, controller.state.value)
        // packRepository.put fires exactly once -- the lastPlayed bump.
        // No cached-manifest write happens because the instance arrived
        // pre-populated; if the fetch path had fired, mockk would not
        // be able to stub SmrtPackClient under JDK 25 (per the file
        // header) and the launch coroutine would have thrown.
        coVerify(exactly = 1) { packRepository.put(any()) }
        assertTrue(captured.captured.lastPlayedEpochOrZero > 0)
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
    ): hivens.core.data.PackInstance {
        val instance = hivens.core.data.PackInstance(
            id                    = id,
            packRef               = hivens.core.data.PackReference(
                origin  = hivens.core.data.PackOrigin.Mirror,
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

        val process = mockk<Process>()
        every { process.waitFor() } returns 0
        val sessionPassed = slot<SessionData>()
        coEvery {
            launcherService.launchPackClient(
                sessionData        = capture(sessionPassed),
                manifest           = any(),
                runtime            = any(),
                clientRootPath     = any(),
                javaPathOverride   = any(),
                allocatedMemoryMB  = any(),
                displayName        = any(),
                onLog              = any(),
            )
        } returns process
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
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    @Test
    fun `pack with Industrial display name synthesizes SC requirement when manifest has none`() = runTest {
        every { settingsService.getSettings() } returns SettingsData()

        // No authRequirement on the manifest; the name-based fallback
        // recognises "Industrial" and synthesizes SC("Industrial").
        // Same no-password setup, so the precondition surfaces.
        val instance = scBoundPackInstance(
            packId          = "industrial",
            displayName     = "Industrial",
            authRequirement = null,
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
            "Industrial fallback must drive the same precondition surface as an explicit requirement",
        )
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
            launcherService.launchPackClient(any(), any(), any(), any(), any(), any(), any(), any())
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
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any(), any())
        }

        val process = mockk<Process>()
        every { process.waitFor() } returns 137 // SIGKILL exit code
        every { process.destroy() } just runs
        coEvery {
            launcherService.launchClientWithLogs(any(), any(), any(), any(), any(), any())
        } returns process

        val controller = newController(this)
        controller.launch(SessionData(playerName = "tester", cachedPassword = "pw"), server)
        advanceUntilIdle()

        val state = controller.state.value
        assertIs<LaunchState.Error>(state)
        assertEquals(LaunchError.ExitCode(137), state.reason)
    }

}
