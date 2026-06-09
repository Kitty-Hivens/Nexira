package hivens.launcher

import hivens.auth.AuthProvider
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.launcher.smrt.SmartyModPlanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.io.path.deleteRecursively

class AutoSyncServiceTest {

    private lateinit var sandbox: Path
    private lateinit var authService: AuthProvider
    private lateinit var downloadService: IFileDownloadService
    private lateinit var manifestProcessor: IManifestProcessorService
    private lateinit var manifestCache: ManifestCache
    private var stubCredentials: SessionData? = null
    private lateinit var service: AutoSyncService

    @BeforeTest
    fun setUp() {
        sandbox = Files.createTempDirectory("aura-autosync-test-")
        authService = mockk()
        downloadService = mockk()
        manifestProcessor = mockk()
        // ManifestCache is a final class -- relaxed mockk so the
        // 2FA fallback path's loadManifest call returns null without
        // a per-test setup. Tests that exercise the cache-hit branch
        // can override per-test.
        manifestCache = mockk(relaxed = true)
        stubCredentials = null

        every { manifestProcessor.calculateIgnoredFiles(any(), any()) } returns emptySet()

        // Smarty planning is exercised in SmartyModPlannerTest; here the helper
        // never resolves and both Smarty settings are off, so the plan is a
        // no-op and these tests stay focused on the queue / state machine.
        val smartyPlanner = SmartyModPlanner(resolveHelper = { null }, manifestProcessor = manifestProcessor)

        service = AutoSyncService(
            authService = authService,
            downloadService = downloadService,
            manifestProcessor = manifestProcessor,
            manifestCache = manifestCache,
            dataDirectory = sandbox,
            credentialsProvider = { stubCredentials },
            optionalModsStateProvider = { emptyMap() },
            smartyPlanner = smartyPlanner,
            settingsProvider = { SettingsData(useOpenSmrtHelper = false, strictModVerification = false) },
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun makeServer(id: String): ServerProfile = ServerProfile(
        name = id,
        version = "1.21.1",
        ip = "skyblock.smartycraft.ru",
        port = 25571,
        assetDir = id,
    )

    private fun installPack(id: String) {
        val dir = sandbox.resolve("clients/$id")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("placeholder.txt"), "exists")
    }

    private fun fakeCreds(): SessionData = SessionData(
        playerName = "NoLikeHumans",
        cachedPassword = "topsecret",
    )

    @Test
    fun `skips when no cached credentials`() = runTest {
        stubCredentials = null

        service.syncAll(listOf(makeServer("Industrial")))

        val state = service.snapshot.value.overall
        assertTrue(state is AutoSyncService.OverallState.Done)
        assertEquals(0, state.succeeded)
        assertEquals(1, state.skipped)  // skipped because no creds, not because no install
    }

    @Test
    fun `skips when password is blank`() = runTest {
        stubCredentials = SessionData(
            playerName = "NoLikeHumans",
            cachedPassword = "",  // blank
        )

        service.syncAll(listOf(makeServer("Industrial")))

        val state = service.snapshot.value.overall
        assertTrue(state is AutoSyncService.OverallState.Done)
        assertEquals(1, state.skipped)
    }

    @Test
    fun `skips servers whose client dir does not exist`() = runTest {
        stubCredentials = fakeCreds()
        // Install only Industrial; Galaxy has no clients/ dir
        installPack("Industrial")

        val s1 = makeServer("Industrial")
        val s2 = makeServer("Galaxy")

        coEvery { authService.login(any(), any(), "Industrial") } returns SessionData()
        coEvery {
            downloadService.processSession(
                session = any(),
                serverId = "Industrial",
                targetDir = any(),
                extraCheckSum = any(),
                ignoredFiles = any(),
                messageUI = any(),
                progressUI = any(),
            )
        } returns Unit

        service.syncAll(listOf(s1, s2))

        val state = service.snapshot.value.overall
        assertTrue(state is AutoSyncService.OverallState.Done)
        assertEquals(1, state.succeeded)
        assertEquals(0, state.failed)
        assertEquals(1, state.skipped)
        coVerify(exactly = 0) { authService.login(any(), any(), "Galaxy") }
    }

    @Test
    fun `per-server failure does not abort the rest`() = runTest {
        stubCredentials = fakeCreds()
        installPack("Industrial")
        installPack("Galaxy")
        installPack("Create")

        coEvery { authService.login(any(), any(), "Industrial") } throws RuntimeException("auth-fail")
        coEvery { authService.login(any(), any(), "Galaxy") } returns SessionData()
        coEvery { authService.login(any(), any(), "Create") } returns SessionData()
        coEvery {
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any())
        } returns Unit

        service.syncAll(listOf(makeServer("Industrial"), makeServer("Galaxy"), makeServer("Create")))

        val state = service.snapshot.value.overall
        assertTrue(state is AutoSyncService.OverallState.Done)
        assertEquals(2, state.succeeded)
        assertEquals(1, state.failed)
        assertEquals(0, state.skipped)

        // Per-server states reflect the outcome
        val states = service.snapshot.value.perServer
        assertEquals(AutoSyncService.ServerState.FAILED, states["Industrial"])
        assertEquals(AutoSyncService.ServerState.SYNCED, states["Galaxy"])
        assertEquals(AutoSyncService.ServerState.SYNCED, states["Create"])
    }

    @Test
    fun `processes servers in queue order`() = runTest {
        stubCredentials = fakeCreds()
        installPack("Industrial")
        installPack("Galaxy")
        installPack("Create")

        coEvery { authService.login(any(), any(), any()) } returns SessionData()
        coEvery {
            downloadService.processSession(any(), any(), any(), any(), any(), any(), any())
        } returns Unit

        service.syncAll(listOf(makeServer("Industrial"), makeServer("Galaxy"), makeServer("Create")))

        coVerifyOrder {
            authService.login(any(), any(), "Industrial")
            authService.login(any(), any(), "Galaxy")
            authService.login(any(), any(), "Create")
        }
    }

    @Test
    fun `empty installed set still emits Done`() = runTest {
        stubCredentials = fakeCreds()
        // No packs installed

        service.syncAll(listOf(makeServer("Industrial"), makeServer("Galaxy")))

        val state = service.snapshot.value.overall
        assertTrue(state is AutoSyncService.OverallState.Done)
        assertEquals(0, state.succeeded)
        assertEquals(0, state.failed)
        assertEquals(2, state.skipped)
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }
}
