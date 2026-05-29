package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.api.model.ServerProfile
import hivens.launcher.LauncherService.Companion.normalizeMemory
import hivens.launcher.LauncherService.Companion.resolveJavaPath
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.runtime.RuntimeProvisioner
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ── resolveJavaPath ──────────────────────────────────────────────────────

    private class FakeJavaManager(
        private val result: Path? = null,
        private val throws: Boolean = false
    ) : IJavaManager {
        override suspend fun getJavaPath(version: String): Path =
            if (throws) throw IOException("simulated download failure")
            else result ?: throw IOException("not available")
        override suspend fun getJavaPathForMajor(javaMajor: Int): Path =
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
        val process = service.launchClientWithLogs(
            sessionData = session,
            serverProfile = serverProfile,
            clientRootPath = clientRoot,
            javaExecutablePath = clientRoot / "ghost-java",  // never used; profile.javaPath wins
            allocatedMemoryMB = 4096,
        ) { msg, type -> captured.add(msg to type) }

        // /usr/bin/true exits immediately with 0. A generous 5s deadline
        // covers slow CI runners; on a fast laptop this returns in <50ms.
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "spawned process must exit within 5s")
        assertEquals(0, process.exitValue(), "/usr/bin/true exits 0; nonzero means ProcessBuilder broke")

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
}
