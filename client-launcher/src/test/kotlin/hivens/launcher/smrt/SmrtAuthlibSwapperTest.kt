package hivens.launcher.smrt

import hivens.launcher.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmrtAuthlibSwapperTest {

    private lateinit var dir: Path
    private val bytes = "patched-smartycraft-authlib".toByteArray()
    private val goodMd5 = md5Hex(bytes)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("authlib-swap-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** `Industrial/<dir>/<fileName>` with the given md5. */
    private fun manifest(dir: String, fileName: String, md5: String): FileManifest = FileManifest(
        directories = mapOf(
            "Industrial" to FileManifest(
                directories = mapOf(
                    dir to FileManifest(
                        files = mapOf(fileName to FileData(md5 = md5, size = bytes.size.toLong())),
                    ),
                ),
            ),
        ),
    )

    private fun swapper(handlerBytes: ByteArray): SmrtAuthlibSwapper {
        val client = HttpClient(MockEngine) {
            engine { addHandler { respond(ByteReadChannel(handlerBytes), HttpStatusCode.OK) } }
        }
        return SmrtAuthlibSwapper(testTransferEngine(HttpClientProvider { client }), ServerProtocolConfig(), dir)
    }

    @Test
    fun `downloads + md5-verifies the patched authlib and caches it per server`() = runBlocking {
        val out = swapper(bytes).ensurePatchedAuthlib("Industrial", manifest("libraries-1.12.2", "authlib-1.5.25.jar", goodMd5))
        assertNotNull(out, "patched authlib should resolve")
        assertTrue(Files.isRegularFile(out), "cached jar must exist on disk")
        assertContentEquals(bytes, Files.readAllBytes(out), "cached bytes must be the downloaded payload")
        assertEquals("authlib-1.5.25.jar", out.fileName.toString(), "cached under the SC filename")
        assertEquals("Industrial", out.parent.fileName.toString(), "cache is per-server")
        assertEquals("smrt-authlib", out.parent.parent.fileName.toString(), "under the smrt-authlib cache root")
    }

    @Test
    fun `matches authlib by artifact even when the SC version differs from vanilla`() = runBlocking {
        // SC freezes its own authlib version; the matcher is by artifact, not an
        // expected filename, so a differing version still resolves (the BLOCKER).
        val out = swapper(bytes).ensurePatchedAuthlib("Industrial", manifest("libraries-1.12.2", "authlib-1.5.21.jar", goodMd5))
        assertNotNull(out, "a differing SC authlib version must still resolve")
        assertEquals("authlib-1.5.21.jar", out.fileName.toString(), "cached under SC's own filename")
    }

    @Test
    fun `ignores an authlib jar that is not under a libraries path`() = runBlocking {
        // A mod basenamed authlib-*.jar outside libraries/ must not be mistaken for it.
        assertNull(swapper(bytes).ensurePatchedAuthlib("Industrial", manifest("mods", "authlib-1.5.25.jar", goodMd5)))
    }

    @Test
    fun `accepts the download without hashing when the manifest md5 is the any sentinel`() = runBlocking {
        val out = swapper(bytes).ensurePatchedAuthlib("Industrial", manifest("libraries-1.12.2", "authlib-1.5.25.jar", "any"))
        assertNotNull(out, "the SC 'any' md5 sentinel means skip-verify, not fail-closed")
        assertContentEquals(bytes, Files.readAllBytes(out))
    }

    @Test
    fun `returns null when the authlib is not in the manifest`() = runBlocking {
        val empty = FileManifest(directories = mapOf("Industrial" to FileManifest()))
        assertNull(swapper(bytes).ensurePatchedAuthlib("Industrial", empty))
    }

    @Test
    fun `returns null and discards the file on md5 mismatch`() = runBlocking {
        val out = swapper(bytes).ensurePatchedAuthlib("Industrial", manifest("libraries-1.12.2", "authlib-1.5.25.jar", "00deadbeef"))
        assertNull(out, "a manifest md5 that the payload does not match must fail closed")
        val dest = dir.resolve("smrt-authlib").resolve("Industrial").resolve("authlib-1.5.25.jar")
        assertTrue(!Files.exists(dest), "the unverified download must be discarded, not cached")
    }

    @Test
    fun `returns null when the session has no file manifest`() = runBlocking {
        assertNull(swapper(bytes).ensurePatchedAuthlib("Industrial", null))
    }

    private companion object {
        fun md5Hex(b: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}
