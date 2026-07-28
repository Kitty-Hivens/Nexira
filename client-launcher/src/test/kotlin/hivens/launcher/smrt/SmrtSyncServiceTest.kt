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
import java.io.IOException
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

    /**
     * [failDownloads] cuts the first N mod-file responses the way a middlebox cuts a
     * transfer: the request is answered and the body then fails to arrive. The
     * manifest is always served, since a pack that cannot be described never gets as
     * far as touching the directory.
     */
    private fun syncService(failDownloads: Int = 0): SmrtSyncService {
        var cuts = failDownloads
        val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                REQ_URL, OPT_URL -> {
                    if (cuts > 0) {
                        cuts--
                        throw IOException("stream was reset: PROTOCOL_ERROR")
                    }
                    val bytes = if (req.url.toString() == REQ_URL) reqBytes else optBytes
                    respond(ByteReadChannel(bytes), HttpStatusCode.OK)
                }
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

    // --- a broken transfer must not cost the instance its contents ---

    @Test
    fun `a cut transfer is retried rather than failing the pack`() = runTest {
        val dir = tempDir("sync-retry")
        syncService(failDownloads = 2).sync("test", dir)
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "the retry completed the transfer")
    }

    @Test
    fun `a failed sync leaves what was already installed`() = runTest {
        val dir = tempDir("sync-keep")
        val mods = Files.createDirectories(dir.resolve("mods"))
        Files.writeString(mods.resolve("foreign.jar"), "from another source")

        // Every attempt is cut, so the sync gives up.
        val failed = runCatching { syncService(failDownloads = Int.MAX_VALUE).sync("test", dir) }
        assertTrue(failed.isFailure, "the sync was expected to fail")
        assertTrue(
            Files.exists(mods.resolve("foreign.jar")),
            "a failed install destroyed content it had not replaced",
        )
    }

    @Test
    fun `a completed sync drops the foreign content it replaced`() = runTest {
        val dir = tempDir("sync-drop")
        val mods = Files.createDirectories(dir.resolve("mods"))
        Files.writeString(mods.resolve("foreign.jar"), "from another source")
        Files.createDirectories(mods.resolve("nested")).also {
            Files.writeString(it.resolve("buried.jar"), "nested payload")
        }

        syncService().sync("test", dir)

        assertFalse(Files.exists(mods.resolve("foreign.jar")), "foreign jar survived a completed sync")
        assertFalse(Files.exists(mods.resolve("nested/buried.jar")), "nested payload survived a completed sync")
        assertTrue(Files.exists(mods.resolve("req.jar")), "the pack's own mod is in place")
    }

    private companion object {
        const val MIRROR_BASE = "https://mirror.test"
        const val MANIFEST_URL = "https://mirror.test/v1/packs/test/manifest"
        const val REQ_URL = "https://mirror.test/req.jar"
        const val OPT_URL = "https://mirror.test/opt.jar"
    }
}
