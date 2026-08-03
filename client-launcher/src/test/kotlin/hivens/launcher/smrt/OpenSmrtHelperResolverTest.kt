package hivens.launcher.smrt

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenSmrtHelperResolverTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: Path

    private val descriptorJson = """
        {
          "schema_version": 1,
          "variants": [
            { "mc_prefix": "1.12.2", "tag": "v0.1.0",
              "asset": "open-smrt-network-forge-1.12.2-0.1.0.jar",
              "sha256": "deadbeef", "size_bytes": 100 }
          ]
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("osmrt-resolver-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `descriptor falls back to last-good cache when the live fetch fails`() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    calls++
                    if (calls == 1) {
                        respond(
                            content = ByteReadChannel(descriptorJson.toByteArray()),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond(ByteReadChannel.Empty, HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
        val resolver = OpenSmrtHelperResolver(HttpClientProvider { client }, testTransferEngine(HttpClientProvider { client }), json, dir)

        val first = resolver.fetchDescriptor()
        assertEquals("v0.1.0", first?.variantFor("1.12.2")?.tag, "live fetch parses + caches")

        val second = resolver.fetchDescriptor()
        assertEquals(
            "v0.1.0", second?.variantFor("1.12.2")?.tag,
            "a failed live fetch must serve the last-good descriptor cache, not null",
        )
    }
}
