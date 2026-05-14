package hivens.launcher

import hivens.core.api.AuthService
import hivens.core.api.model.ServerProfile
import hivens.core.api.protocol.LoginResponse
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.GameCommandBuilder
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test-harness chunk: end-to-end orchestration of the launch pipeline using
 * [FakeServerProtocol] for the auth boundary, real (non-mocked)
 * [ManifestProcessorService] / [ClasspathProvider] / [GameCommandBuilder],
 * and a tmp-dir client root with fake jars matching the manifest.
 *
 * Stops short of `ProcessBuilder.start()` — never actually spawns a JVM.
 * Catches orchestration regressions that pure unit tests on each component
 * miss:
 *
 *   - Auth response shape change → SessionData.fileManifest empty →
 *     ClasspathProvider returns "" → JVM dies with "Could not find or load
 *     main class" (silent if no such test exists).
 *   - GameCommandBuilder picking wrong main class for the version field that
 *     auth populates.
 *   - Memory normalization interacting with profile defaults under realistic shape.
 *
 * Pre-Conduit version of this test mocked HTTP at the [io.ktor.client.HttpClient]
 * boundary via MockEngine. Post-Conduit Phase 1 we mock at the
 * [hivens.core.api.interfaces.IServerProtocol] boundary instead — same coverage,
 * less ceremony, doesn't depend on Ktor wire-format details.
 */
class LaunchPipelineIntegrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val tmpRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tmpRoots.forEach { root ->
            runCatching {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tmpRoots.clear()
    }

    private fun makeClientRoot(): Path =
        Files.createTempDirectory("aura-pipeline-").also { tmpRoots.add(it) }

    /**
     * Pre-programmed protocol that returns an OK login carrying a manifest
     * with one library jar.
     */
    private fun protocolWithManifest(libRelPath: String): FakeServerProtocol =
        FakeServerProtocol().apply {
            loginResult = { req ->
                LoginResponse(
                    status = "OK",
                    playername = "TestPlayer",
                    uid = "1",
                    uuid = "550e8400e29b41d4a716446655440000",
                    session = "fake-session-token",
                    money = 0,
                    client = FileManifest(
                        directories = mapOf(
                            "libraries" to FileManifest(
                                files = mapOf(libRelPath to FileData(md5 = "deadbeef", size = 100L))
                            )
                        ),
                        files = emptyMap(),
                    ),
                )
            }
        }

    @Test
    fun `auth via fake protocol produces a SessionData with embedded manifest`() = runTest {
        val protocol = protocolWithManifest("launchwrapper-1.12.jar")
        val session = AuthService(protocol).login("user", "pass", "Industrial")

        assertEquals("TestPlayer", session.playerName)
        assertEquals("550e8400e29b41d4a716446655440000", session.uuid)
        val manifest = session.fileManifest
        assertNotNull(manifest)
        val libsDir = manifest.directories["libraries"]
        assertNotNull(libsDir)
        assertTrue(libsDir.files.keys.any { it == "launchwrapper-1.12.jar" })
    }

    @Test
    fun `full pipeline 1_7_10 — auth manifest builds a non-empty classpath with the jar`() = runTest {
        val clientRoot = makeClientRoot()
        // Place the actual jar on disk so ClasspathProvider's existence check passes.
        val libDir = (clientRoot / "libraries").also { Files.createDirectories(it) }
        Files.createFile(libDir / "launchwrapper-1.12.jar")

        val protocol = protocolWithManifest("launchwrapper-1.12.jar")
        val session = AuthService(protocol).login("user", "pass", "Industrial")

        val manifestProcessor = ManifestProcessorService(json)
        val classpathProvider = ClasspathProvider(manifestProcessor)
        val classpath = classpathProvider.buildClasspath(clientRoot, session.fileManifest!!, emptyList())

        assertTrue(classpath.isNotBlank(), "classpath must not be empty when manifest carries an existing jar")
        assertTrue(classpath.contains("launchwrapper-1.12.jar"), "classpath must reference the manifest jar")
    }

    @Test
    fun `full pipeline 1_7_10 — GameCommandBuilder produces a valid launch command`() = runTest {
        val clientRoot = makeClientRoot()
        val libDir = (clientRoot / "libraries").also { Files.createDirectories(it) }
        Files.createFile(libDir / "launchwrapper-1.12.jar")

        val protocol = protocolWithManifest("launchwrapper-1.12.jar")
        val session = AuthService(protocol).login("user", "pass", "Industrial")

        val manifestProcessor = ManifestProcessorService(json)
        val classpath = ClasspathProvider(manifestProcessor)
            .buildClasspath(clientRoot, session.fileManifest!!, emptyList())

        val command = GameCommandBuilder().build(
            javaExec = "/fake/java",
            memoryMB = 4096,
            clientRoot = clientRoot,
            serverProfile = ServerProfile(
                name = "Industrial",
                version = "1.7.10",
                ip = "127.0.0.1",
                port = 25565,
                assetDir = "Industrial"
            ),
            session = session,
            userProfile = hivens.core.data.InstanceProfile(memoryMb = 4096),
            classpath = classpath
        )

        // Spot-check the orchestration output rather than the whole 50+ entry list:
        assertEquals("/fake/java", command.first())
        assertTrue(command.contains("-Xmx4096M"), "memory flag must reflect normalized allocation")
        assertTrue(command.contains("net.minecraft.launchwrapper.Launch"),
            "1.7.10 main class must be Launchwrapper")
        assertTrue(command.contains("--tweakClass"), "1.7.10 must pass a tweakClass arg")
        assertTrue(command.contains("cpw.mods.fml.common.launcher.FMLTweaker"),
            "1.7.10 tweak must be the legacy FMLTweaker (cpw.mods.fml namespace)")
        // Player identity flowed from FakeServerProtocol response through the entire pipeline.
        val uuidIdx = command.indexOf("--uuid")
        assertTrue(uuidIdx >= 0, "--uuid arg must be present")
        assertEquals("550e8400e29b41d4a716446655440000", command[uuidIdx + 1])
    }

    @Test
    fun `auth failure on fake protocol surfaces as AuthException — no command construction proceeds`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loginResult = { LoginResponse(status = "PASSWORD") }
        }
        val ex = kotlin.runCatching {
            AuthService(protocol).login("user", "wrong", "Industrial")
        }.exceptionOrNull()
        assertNotNull(ex, "PASSWORD-status response must throw, not produce a SessionData")
        // We deliberately don't assert the specific exception type here — the
        // value of this test is "auth failure stops the pipeline before any
        // launch artefacts get assembled". The AuthServiceTest suite covers
        // the exception type details.
    }

    @Test
    fun `pipeline degrades gracefully when manifest is empty — classpath empty, no crash`() = runTest {
        val clientRoot = makeClientRoot()
        // No libraries dir on disk; no manifest entries either.
        val emptyManifest = FileManifest(directories = emptyMap(), files = emptyMap())

        val classpath = ClasspathProvider(ManifestProcessorService(json))
            .buildClasspath(clientRoot, emptyManifest, emptyList())

        // Empty is acceptable here — the LauncherController offline path catches
        // this and bails with a user-facing error before invoking GameCommandBuilder.
        // The point is that ClasspathProvider doesn't throw on an empty manifest.
        assertEquals("", classpath)
    }
}
