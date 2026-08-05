package hivens.launcher.di

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IServerProtocol
import hivens.launcher.network.NetworkState
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.protocol.LauncherHashCache
import okhttp3.Call
import okhttp3.OkHttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The channel graph is assembled from lambdas, so a binding that no longer
 * exists compiles fine and fails at first resolution -- during Koin start, on
 * the boot path. These resolve every network binding a launch touches, which is
 * the cheapest thing that turns that crash into a red test.
 */
class NetworkModuleGraphTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-network-graph-test-")
        startKoin { modules(module { single { dataDir } }, networkModule) }
    }

    @AfterTest
    fun teardown() {
        NetworkState.clearForTests()
        stopKoin()
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    @Test
    fun `every network binding resolves`() {
        val koin = org.koin.core.context.GlobalContext.get()
        koin.get<ServerProtocolConfig>()
        koin.get<OkHttpClient>(named("direct"))
        koin.get<OkHttpClient>(named("insecure"))
        koin.get<HttpClientProvider>()
        koin.get<HttpClientProvider>(named("direct"))
        koin.get<HttpClientProvider>(named("insecure"))
        koin.get<Call.Factory>()
        koin.get<LauncherHashCache>()
        koin.get<IServerProtocol>()
        koin.get<IServerProtocol>(named("insecure"))
    }

    @Test
    fun `the insecure channel is a distinct client from the direct one`() {
        val koin = org.koin.core.context.GlobalContext.get()
        // The bypass exists to drop certificate verification for one host. If
        // both qualifiers collapsed onto the same client, accepting a bypass
        // would either do nothing or widen to every host we talk to.
        assertNotSame(
            koin.get<OkHttpClient>(named("direct")),
            koin.get<OkHttpClient>(named("insecure")),
        )
    }

    @Test
    fun `the default channel follows the bypass state per request`() {
        val koin = org.koin.core.context.GlobalContext.get()
        val provider = koin.get<HttpClientProvider>()
        val host = koin.get<ServerProtocolConfig>().sslBypassHost

        val strict = provider.current
        NetworkState.grantBypass(host, Instant.now().plusSeconds(60))
        val bypassed = provider.current
        NetworkState.revokeBypass(host)

        // The provider is a singleton, so a grant that arrives mid-session only
        // reaches the next call if the selector re-reads NetworkState rather
        // than capturing a client at construction.
        assertNotSame(strict, bypassed)
        assertSame(strict, provider.current)
    }
}
