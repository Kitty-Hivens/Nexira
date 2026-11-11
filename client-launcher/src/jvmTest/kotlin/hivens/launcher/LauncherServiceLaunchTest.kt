package hivens.launcher

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.SpawnResult
import hivens.core.logging.Redactor
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.GameLaunch
import hivens.launcher.launch.GameHost
import hivens.launcher.launch.PackPrepBlocked
import hivens.launcher.launch.PreparedRuntime
import hivens.launcher.launch.RuntimeRequest
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.smrt.SmrtAuthlibSwapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the launch service does around the host, with a host that records instead of
 * running a game.
 *
 * Until the JVM and the process were behind [GameHost] none of this could be reached
 * without starting a real Java: the order of the steps, what the host is asked for,
 * what a refusal at each point ends as, and when the session token stops being masked
 * in the game's output. Provisioning is replaced by a runtime fixed in advance, so
 * what is under test is the orchestration and not the network.
 */
class LauncherServiceLaunchTest {

    private val token = "session-token-0123456789abcdef"

    @BeforeTest
    fun clean() = Redactor.forgetSecrets()

    @AfterTest
    fun cleanAfter() = Redactor.forgetSecrets()

    // -- the stand-in host -----------------------------------------------------

    private class FakeHandle : LaunchHandle {
        override suspend fun awaitExit(): Int = 0
        override fun terminate() = Unit
        override val stdin: OutputStream = ByteArrayOutputStream()
    }

    private class FakeHost : GameHost {
        val requests = mutableListOf<RuntimeRequest>()
        val launches = mutableListOf<GameLaunch>()
        val workDirs = mutableListOf<Path>()
        val handle = FakeHandle()
        var onDrained: (() -> Unit)? = null
        var afterExit: (() -> Unit)? = null

        var refusePrepare: Throwable? = null
        var failStart: Throwable? = null

        override suspend fun prepare(request: RuntimeRequest, onLog: (String, LauncherLogType) -> Unit): PreparedRuntime {
            requests += request
            refusePrepare?.let { throw it }
            return object : PreparedRuntime {
                override val description: String = "fake-java"

                override fun start(
                    launch: GameLaunch,
                    workDir: Path,
                    boundLaunch: Boolean,
                    onLog: (String, LauncherLogType) -> Unit,
                    onDrained: () -> Unit,
                    afterExit: () -> Unit,
                ): LaunchHandle {
                    failStart?.let { throw it }
                    launches += launch
                    workDirs.add(workDir)
                    this@FakeHost.onDrained = onDrained
                    this@FakeHost.afterExit = afterExit
                    return handle
                }
            }
        }
    }

    // -- the service under test ------------------------------------------------

    private val root: Path = Files.createTempDirectory("nexira-launch").also { it.toFile().deleteOnExit() }
    private val clientRoot: Path = Files.createDirectories(root.resolve("instance"))

    private val resolved = ResolvedRuntime(
        libraries = emptyList(),
        clientJar = root.resolve("client.jar"),
        mainClass = "net.minecraft.launchwrapper.Launch",
        assetIndexId = "1.12",
        javaMajor = 21,
        loaderVersion = "14.23.5.2860",
    )

    private val manifest = CachedManifestSnapshot(
        minecraftVersion = "1.12.2",
        loaderName = "forge",
        loaderVersion = "14.23.5.2860",
        javaMajor = 8,
    )

    private fun service(host: FakeHost): LauncherService {
        val provisioner = mockk<RuntimeProvisioner>()
        coEvery { provisioner.ensureRuntime(any(), any(), any(), any(), any()) } returns resolved
        val agents = mockk<AgentExtractor>(relaxed = true)
        every { agents.ensureAuthlibAgent() } returns null
        every { agents.ensureProfilerAgent() } returns null
        return LauncherService(
            host = host,
            envPreparer = mockk<EnvironmentPreparer>(relaxed = true),
            commandBuilder = GameCommandBuilder(),
            runtimeProvisioner = provisioner,
            profilerStore = mockk<ProfilerProfileStore>(relaxed = true),
            agentExtractor = agents,
            authlibSwapper = mockk<SmrtAuthlibSwapper>(relaxed = true),
            sharedAssetsDir = root.resolve("assets"),
            sharedLibrariesDir = root.resolve("libraries"),
        )
    }

    private suspend fun LauncherService.launch(
        runtime: InstanceRuntime = InstanceRuntime(),
        javaOverride: Path? = null,
        seal: (suspend () -> Boolean)? = null,
    ): SpawnResult = launchPackClient(
        sessionData = SessionData(playerName = "Steve", uuid = "00000000000000000000000000000001", accessToken = token),
        manifest = manifest,
        runtime = runtime,
        clientRootPath = clientRoot,
        javaPathOverride = javaOverride,
        redirectAuthHost = false,
        useNetworkAgent = false,
        seal = seal,
        displayName = "Test pack",
        onLog = { _, _ -> },
    )

    // -- what the host is asked ------------------------------------------------

    @Test
    fun `the host is asked for the Java major the loader declared, with the pin and the override`() = runTest {
        val host = FakeHost()
        service(host).launch(
            runtime = InstanceRuntime(javaPath = "/opt/pinned/bin/java"),
            javaOverride = Path.of("/opt/override/bin/java"),
        )

        assertEquals(
            RuntimeRequest(
                // The loader's own declaration beats the manifest's.
                javaMajor = 21,
                pinnedJava = "/opt/pinned/bin/java",
                overrideJava = Path.of("/opt/override/bin/java"),
                boundLaunch = true,
            ),
            host.requests.single(),
        )
    }

    @Test
    fun `the game is started with the launch the builder produced, in the instance directory`() = runTest {
        val host = FakeHost()
        val result = service(host).launch()

        val started = assertIs<SpawnResult.Started>(result)
        assertEquals(host.handle, started.handle)
        assertEquals("14.23.5.2860", started.resolvedLoaderVersion)

        val launch = host.launches.single()
        assertEquals("net.minecraft.launchwrapper.Launch", launch.mainClass)
        assertTrue("-cp" in launch.jvmArgs, "the classpath belongs to the JVM's options: ${launch.jvmArgs}")
        assertTrue(launch.mainClass !in launch.jvmArgs, "and the main class is not among them")
        assertEquals(listOf("--username", "Steve"), launch.gameArgs.take(2))
        assertEquals(clientRoot, host.workDirs.single())
    }

    // -- refusals ---------------------------------------------------------------

    @Test
    fun `a runtime the host refuses ends the launch with the reason it gave and starts nothing`() = runTest {
        val host = FakeHost().apply { refusePrepare = PackPrepBlocked(LaunchError.Internal("java-not-executable")) }

        val result = service(host).launch()

        assertEquals(SpawnResult.Failed(LaunchError.Internal("java-not-executable")), result)
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun `a seal that says the instance changed stops the launch before the host starts it`() = runTest {
        val host = FakeHost()

        val result = service(host).launch(seal = { false })

        assertEquals(SpawnResult.Failed(LaunchError.ContentChangedDuringLaunch), result)
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun `a cancelled launch is cancelled and not reported as a failure`() = runTest {
        val host = FakeHost().apply { refusePrepare = CancellationException("the user pressed cancel") }

        assertFailsWith<CancellationException> { service(host).launch() }
    }

    @Test
    fun `a host that cannot start the game ends as an internal failure carrying its message`() = runTest {
        val host = FakeHost().apply { failStart = IOException("cannot run program") }

        val result = service(host).launch()

        assertEquals(SpawnResult.Failed(LaunchError.Internal("cannot run program")), result)
    }

    // -- the session token --------------------------------------------------------

    @Test
    fun `the token is masked while the game may still be writing and released once it has drained`() = runTest {
        val host = FakeHost()
        service(host).launch()

        assertNotEquals(token, Redactor.redact(token), "masked from the moment the process can exist")

        host.onDrained!!.invoke()

        assertEquals(token, Redactor.redact(token), "released when both of its streams have ended")
    }

    @Test
    fun `a failed start does not leave the token masked`() = runTest {
        val host = FakeHost().apply { failStart = IOException("boom") }

        service(host).launch()

        assertEquals(token, Redactor.redact(token))
    }
}
