package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.launch.SpawnResult
import hivens.core.api.model.ServerProfile
import hivens.launcher.LauncherService.Companion.adaptiveApplies
import hivens.launcher.LauncherService.Companion.baselineMemory
import hivens.launcher.LauncherService.Companion.findAuthlibLibrary
import hivens.launcher.LauncherService.Companion.normalizeMemory
import hivens.launcher.LauncherService.Companion.resolveJavaPath
import hivens.launcher.LauncherService.Companion.swapAuthlibPath
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.smrt.OpenSmrtHelperResolver
import hivens.launcher.smrt.SmrtAuthlibSwapper
import hivens.test.buildMockClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Probe-lite scope: verifies the policies extracted from [LauncherService] into
 * its internal companion (memory normalization + Java-path resolution). Both
 * live as companion functions specifically so tests can hit them without
 * having to construct the full collaborator graph or spawn a real process.
 *
 * Java-path resolution depends on [IJavaManager] which is now an interface
 * (Test-harness chunk) -- the fake below replaces the real download path.
 */
class LauncherServiceTest {

    // ── normalizeMemory ──────────────────────────────────────────────────────

    @Test
    fun `normalizeMemory bumps tiny allocations up to 1024`() {
        assertEquals(1024, normalizeMemory(profileMb = 0, allocatedMb = 512))
        assertEquals(1024, normalizeMemory(profileMb = 256, allocatedMb = 8192))
        assertEquals(1024, normalizeMemory(profileMb = 0, allocatedMb = 0))
    }

    @Test
    fun `normalizeMemory honours profile when positive`() {
        assertEquals(2048, normalizeMemory(profileMb = 2048, allocatedMb = 4096))
    }

    @Test
    fun `normalizeMemory falls back to allocated when profile is zero`() {
        assertEquals(4096, normalizeMemory(profileMb = 0, allocatedMb = 4096))
    }

    @Test
    fun `normalizeMemory leaves comfortable values alone`() {
        assertEquals(8192, normalizeMemory(profileMb = 0, allocatedMb = 8192))
        assertEquals(768, normalizeMemory(profileMb = 768, allocatedMb = 0))
    }

    // ── adaptiveApplies (the per-instance gate) ──────────────────────────────

    @Test
    fun `adaptiveApplies needs the global signal on and the instance unpinned`() {
        assertTrue(adaptiveApplies(adaptiveEnabled = true, fixedMemory = false))   // default: adaptive on
        assertFalse(adaptiveApplies(adaptiveEnabled = true, fixedMemory = true))   // pinned -> off
        assertFalse(adaptiveApplies(adaptiveEnabled = false, fixedMemory = false)) // global off -> off
        assertFalse(adaptiveApplies(adaptiveEnabled = false, fixedMemory = true))  // both off
    }

    // ── baselineMemory (tier resolution) ─────────────────────────────────────

    @Test
    fun `baselineMemory honours an explicit pin, uncapped`() {
        // Fixed: a deliberate value is respected even above 75% of a small machine.
        assertEquals(6144, baselineMemory(fixedMemory = true, profileMb = 6144, allocatedMb = 4096, systemRamMb = 4096))
    }

    @Test
    fun `baselineMemory still floors a tiny pin`() {
        assertEquals(1024, baselineMemory(fixedMemory = true, profileMb = 256, allocatedMb = 0, systemRamMb = 16384))
    }

    @Test
    fun `unpinned ignores the stored value and uses the Automatic baseline`() {
        // The cold-start over-allocation regression: a 4 GB box no longer gets 6144.
        assertEquals(2457, baselineMemory(fixedMemory = false, profileMb = 6144, allocatedMb = 6144, systemRamMb = 4096))
    }

    @Test
    fun `unpinned scales the Automatic baseline with the machine`() {
        assertEquals(9830, baselineMemory(fixedMemory = false, profileMb = 4096, allocatedMb = 4096, systemRamMb = 16384))
    }

    // ── resolveJavaPath ──────────────────────────────────────────────────────

    private class FakeJavaManager(
        private val result: Path? = null,
        private val throws: Boolean = false
    ) : IJavaManager {
        override suspend fun getJavaPath(version: String): Path =
            if (throws) throw IOException("simulated download failure")
            else result ?: throw IOException("not available")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path =
            if (throws) throw IOException("simulated download failure")
            else result ?: throw IOException("not available")
    }

    @Test
    fun `resolveJavaPath honours profile path when set, never touching managed Java`() = runTest {
        val profile = InstanceProfile(javaPath = "/custom/java/bin/java")
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = profile,
            defaultPath = Path.of("/never/used"),
            version = "1.21.1"
        )
        assertEquals("/custom/java/bin/java", resolved)
    }

    @Test
    fun `resolveJavaPath returns managed Java when on disk`() = runTest {
        val tmp = makeTempDir()
        val managed = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(result = managed),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = Path.of("/never/used"),
            version = "1.21.1"
        )
        assertEquals(managed.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath falls back to defaultPath when manager throws and default exists`() = runTest {
        val tmp = makeTempDir()
        val default = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = default,
            version = "1.21.1"
        )
        assertEquals(default.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath falls back to defaultPath when managed path doesn't exist`() = runTest {
        val tmp = makeTempDir()
        val default = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(result = tmp / "ghost"),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = default,
            version = "1.21.1"
        )
        assertEquals(default.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath returns plain 'java' when nothing else works`() = runTest {
        val tmp = makeTempDir()
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = tmp / "ghost",
            version = "1.21.1"
        )
        assertEquals("java", resolved)
    }

    // ── launchClientWithLogs: full orchestration ─────────────────────────
    //
    // Spawns a real OS process to exercise the full prepareNatives ->
    // prepareAssets -> classpath -> command -> ProcessBuilder.start ->
    // log-handler-attach chain. Uses `/usr/bin/true` as javaExec so the
    // spawned process exits immediately with 0; the test value is in
    // verifying the orchestrator completes cleanly and the user-facing
    // log messages flow through to onLog.
    //
    // Linux/Mac only -- the no-op `true` binary doesn't have a portable
    // Windows equivalent that works with arbitrary trailing arguments.
    // The Windows orchestration code path is identical and exercised in
    // production; if we needed Windows-host coverage we'd add a separate
    // test with `cmd /c rem`.

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `launchClientWithLogs orchestrates prepare-build-spawn and surfaces logs`() = runTest {
        val workDir = makeTempDir()
        val clientRoot = (workDir / "clients" / "TestServer").also { Files.createDirectories(it) }
        val profilesDir = (workDir / "profiles").also { Files.createDirectories(it) }

        // ── Pre-populate so prepareNatives + prepareAssets short-circuit ──
        //
        // prepareNatives gate looks for `liblwjgl*.so` (on Linux) at the
        // natives dir root; prepareAssets gate counts >= 10 entries under
        // assets/objects. Pre-place both so the orchestrator exercises
        // the env-prepare CALL but the bodies short-circuit without
        // touching the (dead) HttpClient below.
        val nativesDir = (clientRoot / "bin" / "natives-1.7.10").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "liblwjgl.so")
        val assetsObjectsDir = (clientRoot / "assets" / "objects").also { Files.createDirectories(it) }
        for (i in 1..12) Files.writeString(assetsObjectsDir / "asset-$i.txt", "x")

        // One real library jar on disk so ClasspathProvider returns a
        // non-empty classpath (validateLibrary requires Files.exists).
        val libDir = (clientRoot / "libraries").also { Files.createDirectories(it) }
        Files.createFile(libDir / "launchwrapper-1.12.jar")

        // ── Real collaborator graph ──
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val profileManager = ProfileManager(profilesDir, json).also {
            // javaPath non-empty -> resolveJavaPath returns it directly,
            // never touches the IJavaManager. Keeps the test from needing
            // a real BellSoft download path.
            it.saveProfile(InstanceProfile(
                serverId = "TestServer",
                memoryMb = 1024,
                javaPath = "/usr/bin/true",
            ))
        }
        val manifestProcessor = ManifestProcessorService(json)
        val classpathProvider = ClasspathProvider(manifestProcessor, osName = "Linux")
        // EnvironmentPreparer takes an HttpClientProvider for the fallback
        // download path. Our pre-populated natives + assets short-circuit
        // both prepare steps, so a dead client catches any regression
        // that drops us into the download path unintentionally.
        val envPreparer = EnvironmentPreparer(deadHttpClientProvider())
        val commandBuilder = GameCommandBuilder()
        val logHandler = ProcessLogHandler()
        val service = LauncherService(
            profileManager = profileManager,
            javaManager = FakeJavaManager(throws = true),  // never called given profile.javaPath
            envPreparer = envPreparer,
            classpathProvider = classpathProvider,
            commandBuilder = commandBuilder,
            logHandler = logHandler,
            // SC path under test never invokes the provisioner; a dead one satisfies the ctor.
            runtimeProvisioner = RuntimeProvisioner(
                librariesDir = workDir / "libraries",
                assetsDir = workDir / "assets",
                clientProvider = deadHttpClientProvider(),
                json = json,
            ),
            profilerStore = ProfilerProfileStore(json),
            agentExtractor = AgentExtractor(workDir),
            // SC server path never reaches the SC-binding step; dead deps satisfy the ctor.
            authlibSwapper = SmrtAuthlibSwapper(deadHttpClientProvider(), ServerProtocolConfig(), workDir),
            openSmrtResolver = OpenSmrtHelperResolver(deadHttpClientProvider(), json, workDir),
            sharedAssetsDir = workDir / "assets",
            sharedLibrariesDir = workDir / "libraries",
        )

        val session = SessionData(
            playerName = "TestPlayer",
            uuid = "550e8400e29b41d4a716446655440000",
            accessToken = "fake-token",
            uid = "1",
            fileManifest = FileManifest(
                directories = mapOf(
                    "libraries" to FileManifest(
                        files = mapOf("launchwrapper-1.12.jar" to FileData(md5 = "x", size = 0)),
                    ),
                ),
            ),
        )
        val serverProfile = ServerProfile(
            name = "TestServer",
            version = "1.7.10",
            ip = "127.0.0.1",
            port = 25565,
            assetDir = "TestServer",
        )

        // ── Spawn ──
        val captured = CopyOnWriteArrayList<Pair<String, LauncherLogType>>()
        val result = service.launchClientWithLogs(
            sessionData = session,
            serverProfile = serverProfile,
            clientRootPath = clientRoot,
            javaExecutablePath = clientRoot / "ghost-java",  // never used; profile.javaPath wins
            allocatedMemoryMB = 4096,
        ) { msg, type -> captured.add(msg to type) }

        // /usr/bin/true exits immediately with 0; awaitExit() blocks on the real
        // process and returns its code (<50ms on a laptop).
        assertIs<SpawnResult.Started>(result, "spawn must succeed; got $result")
        assertEquals(0, result.handle.awaitExit(), "/usr/bin/true exits 0; nonzero means ProcessBuilder broke")

        // ── Asserts on observable orchestration ──
        // The "Running <server>..." line is the synchronous onLog before spawn.
        assertTrue(
            captured.any { (msg, type) -> type == LauncherLogType.INFO && msg.contains("Running TestServer") },
            "expected 'Running TestServer' INFO log; got: ${captured.map { it.first }}",
        )
        // The "CMD: ..." line is also synchronous and shows the full
        // command. Confirms profile.javaPath flowed into the command and
        // the version-specific main class wired up.
        val cmdLog = captured.firstOrNull { (msg, _) -> msg.startsWith("CMD:") }
        assertTrue(cmdLog != null, "expected 'CMD:' log line")
        val cmdLine = cmdLog.first
        assertTrue(cmdLine.contains("/usr/bin/true"),
            "profile.javaPath must flow into the command; got: $cmdLine")
        assertTrue(cmdLine.contains("net.minecraft.launchwrapper.Launch"),
            "1.7.10 main class must be in the command; got: $cmdLine")
        assertTrue(cmdLine.contains("launchwrapper-1.12.jar"),
            "manifest jar must be in the classpath portion of the command; got: $cmdLine")
        assertTrue(cmdLine.contains("--uuid"),
            "session uuid must be passed to the game command")
    }

    private fun deadHttpClientProvider(): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    error("unexpected HTTP call -- prepareNatives/Assets should have short-circuited")
                }
            }
        }
        return HttpClientProvider { client }
    }

    private fun makeTempDir(): Path = Files.createTempDirectory("launcher-test-").also { it.createDirectories() }

    // ── SC-bound authlib swap (the pure half of applySmrtBinding) ─────────────

    private fun runtimeOf(libs: List<ResolvedLibrary>) = ResolvedRuntime(
        libraries = libs,
        clientJar = Path.of("/libs/client.jar"),
        mainClass = "Main",
        assetIndexId = "1.12",
    )

    private val lwjgl = ResolvedLibrary(MavenCoord("org.lwjgl", "lwjgl", "3.3.1"), Path.of("/libs/lwjgl.jar"))
    private val authlib = ResolvedLibrary(MavenCoord("com.mojang", "authlib", "1.5.25"), Path.of("/libs/authlib-1.5.25.jar"))

    @Test
    fun `findAuthlibLibrary picks the com_mojang authlib entry`() {
        assertEquals(authlib, findAuthlibLibrary(runtimeOf(listOf(lwjgl, authlib))))
    }

    @Test
    fun `findAuthlibLibrary returns null when no authlib is present`() {
        assertEquals(null, findAuthlibLibrary(runtimeOf(listOf(lwjgl))))
    }

    @Test
    fun `swapAuthlibPath repoints only the authlib entry`() {
        val patched = Path.of("/cache/smrt-authlib/Industrial/authlib-1.5.25.jar")
        val out = swapAuthlibPath(runtimeOf(listOf(lwjgl, authlib)), authlib, patched)

        assertEquals(patched, out.libraries.first { it.coord.artifact == "authlib" }.path, "authlib path is swapped")
        assertEquals(lwjgl.path, out.libraries.first { it.coord.artifact == "lwjgl" }.path, "other libraries untouched")
        assertEquals(2, out.libraries.size, "no entry added or dropped")
    }
}
