package hivens.launcher

import hivens.test.MockResponse
import hivens.test.buildMockClient
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ServerRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // region Fixtures

    private fun dashboardOkResponse(serverCount: Int = 2) = buildString {
        append("""{"status":"OK","servers":[""")
        repeat(serverCount) { i ->
            if (i > 0) append(",")
            append("""{"name":"Server$i","address":"play.example.com","port":${25565 + i},"version":"1.7.10","online":10,"max":100}""")
        }
        append("""],"news":[{"id":1,"name":"Big Update","image":"news1","date":1700000000,"views":500}]}""")
    }

    private val updateResponse = """{"status":"UPDATE","servers":[],"news":[]}"""
    private val fakeJarBytes   = "PK\u0003\u0004fake-jar-content-for-hash-test"

    // endregion

    @Test
    fun `fetchDashboard returns servers and news on OK response`() = runTest {
        val result = hivens.core.api.ServerRepository(buildMockClient(dashboardOkResponse(2)), json)
            .fetchDashboard()

        assertEquals("OK", result.status)
        assertEquals(2, result.servers.size)
        assertEquals(1, result.news.size)
        assertEquals("Server0", result.servers[0].id)
    }

    @Test
    fun `fetchDashboard handles empty server list`() = runTest {
        val result = hivens.core.api.ServerRepository(buildMockClient(dashboardOkResponse(0)), json)
            .fetchDashboard()

        assertEquals("OK", result.status)
        assertTrue(result.servers.isEmpty())
    }

    @Test
    fun `fetchDashboard re-fetches after UPDATE and returns final OK response`() = runTest {
        val client = buildMockClient(
            MockResponse(urlContains = "index.php",       body = updateResponse),
            MockResponse(urlContains = "smartycraft.jar", body = fakeJarBytes),
            MockResponse(urlContains = "index.php",       body = dashboardOkResponse(3)),
        )
        val result = hivens.core.api.ServerRepository(client, json).fetchDashboard()

        assertEquals("OK", result.status)
        assertEquals(3, result.servers.size)
    }

    @Test
    fun `fetchDashboard does not loop when JAR download fails after UPDATE`() = runTest {
        val client = buildMockClient(
            MockResponse(urlContains = "index.php",       body = updateResponse),
            MockResponse(urlContains = "smartycraft.jar", body = "", status = HttpStatusCode.NotFound),
        )
        val result = hivens.core.api.ServerRepository(client, json).fetchDashboard()

        assertNotNull(result)
    }

    @Test
    fun `fetchDashboard returns ERROR status on HTTP 500`() = runTest {
        val result = hivens.core.api.ServerRepository(
            buildMockClient(
                body = "Internal Server Error",
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Text.Plain
            ),
            json
        ).fetchDashboard()

        assertEquals("ERROR", result.status)
        assertTrue(result.servers.isEmpty())
    }

    @Test
    fun `fetchDashboard returns ERROR on malformed JSON`() = runTest {
        val result = hivens.core.api.ServerRepository(
            buildMockClient(body = "not json at all {{{{", contentType = ContentType.Text.Plain),
            json
        ).fetchDashboard()

        assertEquals("ERROR", result.status)
    }

    @Test
    fun `fetchDashboard does not loop on repeated UPDATE status`() = runTest {
        val client = buildMockClient(
            MockResponse(urlContains = "index.php",       body = updateResponse),
            MockResponse(urlContains = "smartycraft.jar", body = fakeJarBytes),
            MockResponse(urlContains = "index.php",       body = updateResponse),
        )
        val result = hivens.core.api.ServerRepository(client, json).fetchDashboard()

        assertNotNull(result)
        assertEquals("UPDATE", result.status)
    }

    @Test
    fun `fetchDashboard maps server fields correctly`() = runTest {
        val body = """
            {
                "status": "OK",
                "servers": [{
                    "name": "Nevermine",
                    "address": "play.nevermine.ru",
                    "port": 25566,
                    "version": "1.12.2",
                    "online": 42,
                    "max": 500,
                    "title": "Nevermine Adventures",
                    "extraCheckSum": "abc123"
                }],
                "news": []
            }
        """.trimIndent()
        val server = hivens.core.api.ServerRepository(buildMockClient(body), json)
            .fetchDashboard()
            .servers
            .first()

        assertEquals("Nevermine", server.id)
        assertEquals("play.nevermine.ru", server.ip)
        assertEquals(25566, server.port)
        assertEquals("1.12.2", server.version)
        assertEquals(42, server.online)
        assertEquals("abc123", server.extraCheckSum)
    }
}
