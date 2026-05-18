package hivens.launcher.network

import hivens.launcher.network.NetworkState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ChannelRouter unit tests. Use Ktor MockEngine for the two underlying
 * HttpClients (real fallback semantics, no test doubles for the router itself).
 *
 * NetworkState.forceProxyMode is reset around each test to avoid bleed.
 */
class ChannelRouterTest {

    @BeforeTest
    fun resetNetworkState() {
        NetworkState.setForceProxyMode(false)
    }

    @AfterTest
    fun cleanupNetworkState() {
        NetworkState.setForceProxyMode(false)
    }

    private fun mockClient(name: String, throws: Throwable? = null): HttpClient =
        HttpClient(MockEngine { _ ->
            if (throws != null) throw throws
            respond(
                content = ByteReadChannel("RESPONSE_FROM_$name"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain"),
            )
        })

    @Test
    fun `direct succeeds -- returns direct response, proxy never invoked`() = runTest {
        val direct = mockClient("DIRECT")
        val proxy = mockClient("PROXY")
        val router = ChannelRouter(direct = direct, proxy = proxy)

        val body = router.execute { client -> client.get("https://example.com/").bodyAsText() }
        assertEquals("RESPONSE_FROM_DIRECT", body)
    }

    @Test
    fun `direct fails IOException -- falls back to proxy, returns proxy response`() = runTest {
        val direct = mockClient("DIRECT", throws = IOException("connection reset"))
        val proxy = mockClient("PROXY")
        val router = ChannelRouter(direct = direct, proxy = proxy)

        val body = router.execute { client -> client.get("https://example.com/").bodyAsText() }
        assertEquals("RESPONSE_FROM_PROXY", body)
    }

    @Test
    fun `direct fails SocketException -- also falls back to proxy`() = runTest {
        val direct = mockClient("DIRECT", throws = java.net.SocketException("broken pipe"))
        val proxy = mockClient("PROXY")
        val router = ChannelRouter(direct = direct, proxy = proxy)

        val body = router.execute { client -> client.get("https://example.com/").bodyAsText() }
        assertEquals("RESPONSE_FROM_PROXY", body)
    }

    @Test
    fun `both channels fail -- throws original direct exception`() = runTest {
        val directException = IOException("direct down")
        val direct = mockClient("DIRECT", throws = directException)
        val proxy = mockClient("PROXY", throws = IOException("proxy also down"))
        val router = ChannelRouter(direct = direct, proxy = proxy)

        val ex = assertFailsWith<IOException> {
            router.execute { client -> client.get("https://example.com/").bodyAsText() }
        }
        // Original direct exception preserved (not the proxy one) -- caller's
        // diagnostics see the first failure cause.
        assertEquals("direct down", ex.message)
    }

    @Test
    fun `non-fallbackable exception from direct propagates without fallback`() = runTest {
        // RuntimeException is not IOException -- semantic error, not network.
        val direct = mockClient("DIRECT", throws = RuntimeException("logic bug"))
        val proxy = mockClient("PROXY")
        val router = ChannelRouter(direct = direct, proxy = proxy)

        assertFailsWith<RuntimeException> {
            router.execute { client -> client.get("https://example.com/").bodyAsText() }
        }
        // Proxy not invoked -- no way to assert directly without recording wrapper,
        // but the test design (proxy returns RESPONSE_FROM_PROXY) means if we
        // reach here without that string in result, proxy wasn't called.
    }

    @Test
    fun `forceProxyMode true skips direct entirely`() = runTest {
        NetworkState.setForceProxyMode(true)
        val direct = mockClient("DIRECT")
        val proxy = mockClient("PROXY")
        val router = ChannelRouter(direct = direct, proxy = proxy)

        val body = router.execute { client -> client.get("https://example.com/").bodyAsText() }
        // Direct was alive, but force-proxy bypassed it.
        assertEquals("RESPONSE_FROM_PROXY", body)
    }

    @Test
    fun `forceProxyMode true still throws when proxy fails (no fallback to direct)`() = runTest {
        NetworkState.setForceProxyMode(true)
        val direct = mockClient("DIRECT")
        val proxy = mockClient("PROXY", throws = IOException("proxy down"))
        val router = ChannelRouter(direct = direct, proxy = proxy)

        assertFailsWith<IOException> {
            router.execute { client -> client.get("https://example.com/").bodyAsText() }
        }
    }
}
