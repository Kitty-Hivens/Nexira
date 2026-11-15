package hivens.launcher.di

import hivens.auth.AccountStore
import hivens.auth.AuthProviderRegistry
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.update.PackUpdater
import hivens.launcher.CrashReporter
import hivens.launcher.PackInstallService
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The assembled graph, the way the entry points start it: through the bootstrap's own
 * last step, which is also what keeps the graph out of Koin's global context.
 *
 * The per-module tests prove a module resolves with stand-ins for everything around
 * it. None of them proves that the modules, put together, leave nothing unbound, and
 * that is the property that breaks when a registration moves from one module to
 * another. So this loads the lot, with the paths pointed at a temporary directory,
 * and asks for the things the application is entered through.
 */
class LauncherGraphTest {

    private lateinit var dir: Path
    private lateinit var application: KoinApplication
    private val koin: Koin get() = application.koin

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("nexira-graph")
        val paths = PlatformPaths(
            osName = "Linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { if (it == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        val boot = LauncherBootstrap.finishBoot(
            LauncherBootstrap.Core(paths, CrashReporter(paths)),
            listOf(
                module {
                    // The real registrations resolve the per-user paths of the machine
                    // the test runs on; these replace them.
                    single<PlatformPaths> { paths }
                    single<Path> { paths.dataDir }
                },
            ),
        )
        application = boot.koin
    }

    @AfterTest
    fun stop() {
        val _ = runCatching { koin.get<IPackRepository>().let { (it as? AutoCloseable)?.close() } }
        application.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the entry points of the launcher resolve`() {
        assertNotNull(koin.get<ISettingsService>())
        assertNotNull(koin.get<ILauncherService>())
        assertNotNull(koin.get<LauncherController>())
        assertNotNull(koin.get<IJavaManager>())
        assertNotNull(koin.get<PackUpdater>())
        assertNotNull(koin.get<PackInstallService>())
        assertNotNull(koin.get<AuthProviderRegistry>())
    }

    @Test
    fun `the graph is the entry point's alone and not in Koin's global context`() {
        // Anything in the process can ask the global context, a widget module's jar
        // included, and this graph holds the account store and the session tokens.
        assertNull(GlobalContext.getOrNull(), "the launcher's graph was registered globally")
        assertNotNull(koin.get<AccountStore>())
    }

    @Test
    fun `the account store and the credential store are one object`() {
        // Two instances would mean a login saved through one is invisible to the other.
        assertSame(koin.get<AccountStore>(), koin.get<ICredentialStore>())
    }
}
