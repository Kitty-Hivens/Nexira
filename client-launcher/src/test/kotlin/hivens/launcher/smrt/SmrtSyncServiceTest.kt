package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.launcher.ProtectedPaths
import hivens.launcher.modrinth.ModrinthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmrtSyncServiceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(p: String) = Files.createTempDirectory(p).also { temps.add(it) }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private val reqBytes = "REQUIRED".toByteArray()
    private val optBytes = "OPTIONAL".toByteArray()

    private fun manifest() = """
        {"schema_version":2,"pack_id":"test","pack_version":"1","generated_at":"now",
         "minecraft":{"version":"1.20.1"},"loader":{"name":"fabric","version":"0.19.2"},"java":{"major":17},
         "mods":[
           {"filename":"req.jar","sha1":"${sha1(reqBytes)}","size_bytes":${reqBytes.size},"required":true,"source":{"type":"smrt_static","url":"$REQ_URL"}},
           {"filename":"opt.jar","sha1":"${sha1(optBytes)}","size_bytes":${optBytes.size},"required":false,"default_enabled":false,"source":{"type":"smrt_static","url":"$OPT_URL"}}
         ],"assets":[]}
    """.trimIndent()

    private fun syncService(): SmrtSyncService {
        val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                REQ_URL -> respond(ByteReadChannel(reqBytes), HttpStatusCode.OK)
                OPT_URL -> respond(ByteReadChannel(optBytes), HttpStatusCode.OK)
                else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
        val provider = HttpClientProvider { HttpClient(engine) }
        val client = SmrtPackClient(provider, MIRROR_BASE, json)
        val modrinth = ModrinthClient(provider, json)
        return SmrtSyncService(client, modrinth, ProtectedPaths(tempDir("pp").resolve("pp.json"), json))
    }

    @Test
    fun `optional default-off lands as disabled, required stays active`() = runTest {
        val dir = tempDir("sync")
        syncService().sync("test", dir)
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "required mod active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar")), "default-off optional not active")
        assertTrue(Files.exists(dir.resolve("mods/opt.jar.disabled")), "default-off optional placed as .disabled")
    }

    @Test
    fun `enabledState activates an otherwise default-off optional`() = runTest {
        val dir = tempDir("sync2")
        syncService().sync("test", dir, enabledState = mapOf("req.jar" to true, "opt.jar" to true))
        assertTrue(Files.exists(dir.resolve("mods/opt.jar")), "user-enabled optional is active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar.disabled")), "no leftover .disabled variant")
    }

    private companion object {
        const val MIRROR_BASE = "https://mirror.test"
        const val MANIFEST_URL = "https://mirror.test/v1/packs/test/manifest"
        const val REQ_URL = "https://mirror.test/req.jar"
        const val OPT_URL = "https://mirror.test/opt.jar"
    }
}
