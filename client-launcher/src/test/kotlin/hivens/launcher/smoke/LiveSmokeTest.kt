package hivens.launcher.smoke

import hivens.config.Network
import hivens.config.Protocol
import hivens.core.api.AuthService
import hivens.core.api.HttpClientProvider
import hivens.core.api.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import okhttp3.Protocol as OkProtocol

/**
 * Live smoke tests against the real `smartycraft.ru` API.
 *
 * Answers a single question per CI run: *can we currently authenticate
 * end-to-end against the real upstream*? Distinct from the offline contract
 * tests (which prove our parser matches frozen fixture shapes) and the
 * regular unit tests (which mock the network entirely).
 *
 * **Hard release gate.** Tagged `@SmokeTest` so the regular `:client-launcher:test`
 * task excludes it. The dedicated `:client-launcher:smokeTest` task runs only
 * this class and is wired into `build_release.yml` BEFORE artefact assembly,
 * so a smoke failure halts the release pipeline before any installer is built.
 *
 * **Credentials.** Reads `SMARTY_TEST_USER` / `SMARTY_TEST_PASS` from the
 * environment. Locally these are unset, so [Assumptions.assumeTrue] short-
 * circuits each test as "skipped" rather than failing the build. In CI both
 * are wired through GitHub Secrets on `build_release.yml` and `smoke-daily.yml`.
 *
 * **What we do NOT exercise here:**
 *   - file downloads (slow, large bandwidth, irrelevant to "is auth alive")
 *   - process launch (would require a real game install + JDK + obviously
 *     can't run unattended)
 *   - destructive endpoints (purchases, password change, anything mutating)
 */
@SmokeTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LiveSmokeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var okHttp: OkHttpClient
    private lateinit var ktor: HttpClient
    private lateinit var provider: HttpClientProvider
    private lateinit var serverRepo: ServerRepository
    private lateinit var auth: AuthService
    private lateinit var dataDir: Path

    private val username: String? = System.getenv("SMARTY_TEST_USER")
    private val password: String? = System.getenv("SMARTY_TEST_PASS")

    /** Carried across ordered tests — first test discovers a server id, the next ones use it. */
    @Volatile
    private var discoveredServerId: String? = null

    @BeforeAll
    fun setup() {
        // SOCKS proxy auth — same global Authenticator pattern as production
        // wiring in client-launcher/.../di/Modules.kt. Test tearDown restores
        // the previous Authenticator so we don't leak proxy creds into other
        // suites that might run in the same JVM.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(Network.Proxy.USER, Network.Proxy.PASS.toCharArray())
        })

        okHttp = OkHttpClient.Builder()
            .connectTimeout(Network.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(Network.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(Network.Proxy.HOST, Network.Proxy.PORT)))
            .also {
                if (Network.FORCE_HTTP1_FOR_SMARTYCRAFT) {
                    it.protocols(listOf(OkProtocol.HTTP_1_1))
                }
            }
            .build()

        ktor = HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(ContentNegotiation) { json(this@LiveSmokeTest.json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 600_000
            }
            defaultRequest {
                header("User-Agent", "SMARTYlauncher/${Protocol.MIMIC_LAUNCHER_VERSION}")
                contentType(ContentType.Application.Json)
            }
        }

        provider = HttpClientProvider { ktor }
        dataDir = Files.createTempDirectory("aura-smoke-")
        serverRepo = ServerRepository(provider, json, dataDir.toFile())
        auth = AuthService(provider, json)
    }

    @AfterAll
    fun teardown() {
        runCatching { ktor.close() }
        runCatching {
            Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Authenticator.setDefault(null)
    }

    @Test
    @Order(1)
    fun `dashboard returns at least one server with populated schema`() = runBlocking {
        assumeCredentialsPresent()

        val response = serverRepo.fetchDashboard()

        assertNotNull(response.status, "dashboard response missing 'status' field — schema drift?")
        assertTrue(
            response.servers.isNotEmpty(),
            "dashboard returned zero servers — either upstream is empty or the schema field renamed",
        )

        val first = response.servers.first()
        assertTrue(first.id.isNotBlank(), "server entry has blank id ('name' in wire format)")
        assertTrue(first.ip.isNotBlank(), "server entry has blank address")
        assertTrue(first.port > 0, "server entry has invalid port: ${first.port}")
        // assetDir is derived (defaults to id when wire field is null) — sanity-check
        // the fallback hasn't silently broken.
        assertTrue(first.assetDir.isNotBlank(), "assetDir derivation broken — fallback to id failed")

        discoveredServerId = first.id
    }

    @Test
    @Order(2)
    fun `login produces a fully populated SessionData`() = runBlocking {
        assumeCredentialsPresent()
        val serverId = discoveredServerId
            ?: error("test-order violation: dashboard test must run first to discover serverId")

        val session = auth.login(username!!, password!!, serverId)

        assertNotNull(session.status, "session.status null — auth response missing 'status'")
        assertTrue(session.playerName.isNotBlank(), "session.playerName blank — schema field 'playername' may have renamed")
        assertTrue(session.uuid.isNotBlank(), "session.uuid blank — auth incomplete or schema drift on 'uuid'")
        assertTrue(session.uid.isNotBlank(), "session.uid blank — schema field 'uid' may have changed")
        assertTrue(session.accessToken.isNotBlank(), "session.accessToken blank — generateGameToken returned empty")
        // serverId round-trips so downstream consumers can identify the session
        assertTrue(session.serverId == serverId, "session.serverId did not round-trip from login arg")
    }

    @Test
    @Order(3)
    fun `login response carries a non-trivial file manifest`() = runBlocking {
        assumeCredentialsPresent()
        val serverId = discoveredServerId
            ?: error("test-order violation: dashboard test must run first to discover serverId")

        val session = auth.login(username!!, password!!, serverId)
        val manifest = session.fileManifest

        assertNotNull(manifest, "fileManifest absent — auth response missing 'client' field")
        // Real SmartyCraft manifests always carry at least the root-level file/dir
        // collections. An empty manifest after a successful login is a strong
        // signal of upstream protocol drift.
        val hasAnyEntries = manifest!!.directories.isNotEmpty() || manifest.files.isNotEmpty()
        assertTrue(hasAnyEntries, "manifest has zero directories AND zero files — schema drift on 'client' subtree?")
    }

    @Test
    @Order(4)
    fun `wrong password fails closed without leaking a session`() = runBlocking {
        assumeCredentialsPresent()
        val serverId = discoveredServerId
            ?: error("test-order violation: dashboard test must run first to discover serverId")

        // Negative path — protects against the very specific failure mode of
        // an upstream change that returns OK + empty profile on bad creds
        // (silent auth bypass). Catches the inverse of the positive flow.
        val outcome = runCatching {
            auth.login(username!!, "definitely-not-the-real-password-$${System.currentTimeMillis()}", serverId)
        }
        assertTrue(
            outcome.isFailure,
            "wrong password did NOT throw — upstream may be returning OK on bad creds (auth bypass)",
        )
        // Make sure no SessionData leaked despite the failure
        assertNull(outcome.getOrNull(), "wrong-password call returned a SessionData instead of throwing")
    }

    private fun assumeCredentialsPresent() {
        assumeTrue(
            !username.isNullOrBlank() && !password.isNullOrBlank(),
            "SMARTY_TEST_USER / SMARTY_TEST_PASS not set — skipping live smoke (expected on local dev)",
        )
    }
}
