package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyNews
import hivens.core.api.dto.SmartyServer
import hivens.core.api.protocol.LoaderResponse
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Post-Conduit-Phase-1 ServerRepository test surface. The repository is now
 * a thin adapter — its test coverage focuses on what it actually does:
 * shape conversion from [LoaderResponse] to [hivens.core.api.dto.SmartyResponse]
 * and exception → ERROR fallback.
 *
 * UPDATE recovery, hash refresh, HTTP retries — those moved to
 * [hivens.launcher.protocol.SmartycraftV1Protocol] +
 * [hivens.launcher.protocol.LauncherHashCache] tests.
 */
class ServerRepositoryTest {

    @Test
    fun `fetchDashboard returns servers and news on OK response`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loaderResult = {
                LoaderResponse(
                    status = "OK",
                    servers = listOf(
                        SmartyServer(id = "Server0", ip = "play.example.com", port = 25565, version = "1.7.10"),
                        SmartyServer(id = "Server1", ip = "play.example.com", port = 25566, version = "1.7.10"),
                    ),
                    news = listOf(SmartyNews(id = 1, name = "Big Update", image = "news1", date = 1700000000L)),
                )
            }
        }
        val result = ServerRepository(protocol).fetchDashboard()

        assertEquals("OK", result.status)
        assertEquals(2, result.servers.size)
        assertEquals(1, result.news.size)
        assertEquals("Server0", result.servers[0].id)
        assertEquals(1, protocol.loaderCalls.size)
    }

    @Test
    fun `fetchDashboard handles empty server list`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "OK") }
        }
        val result = ServerRepository(protocol).fetchDashboard()

        assertEquals("OK", result.status)
        assertTrue(result.servers.isEmpty())
        assertTrue(result.news.isEmpty())
    }

    @Test
    fun `fetchDashboard surfaces UPDATE status untouched (protocol's job to recover)`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "UPDATE") }
        }
        val result = ServerRepository(protocol).fetchDashboard()

        assertEquals("UPDATE", result.status)
        assertTrue(result.servers.isEmpty())
    }

    @Test
    fun `fetchDashboard returns ERROR shape when protocol throws`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loaderResult = { throw java.io.IOException("network broken") }
        }
        val result = ServerRepository(protocol).fetchDashboard()

        assertEquals("ERROR", result.status)
        assertTrue(result.message?.contains("network broken") == true)
        assertTrue(result.servers.isEmpty())
    }

    @Test
    fun `fetchDashboard preserves message field from protocol response`() = runTest {
        val protocol = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "ERROR", message = "Custom error from server") }
        }
        val result = ServerRepository(protocol).fetchDashboard()

        assertEquals("ERROR", result.status)
        assertEquals("Custom error from server", result.message)
    }
}
