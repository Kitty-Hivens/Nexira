package hivens.launcher.di

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import hivens.launcher.network.NetworkState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An SSL bypass is a grant for one host. These drive a real handshake against a
 * server holding a self-signed certificate issued to a different name, because
 * the parts that can silently rot are exactly the ones a mock would paper over:
 * whether the peer's name is even available while the certificate is being
 * checked, and whether OkHttp takes the trust-manager overload that carries it.
 *
 * The bypass client used to trust every certificate from every host, so a grant
 * for the SmartyCraft host disabled verification for the process-wide image
 * loader too.
 */
class BypassScopedTlsTest {

    private lateinit var dataDir: Path
    private lateinit var server: HttpsServer
    private var port: Int = 0

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-bypass-tls-test-")
        startKoin { modules(module { single { dataDir } }, networkModule) }
        NetworkState.clearForTests()

        // Issued to a name we never connect to, and signed by nobody: the
        // certificate fails both the chain check and the name check, which is
        // the shape of the outage the bypass exists for.
        val cert = HeldCertificate.Builder()
            .commonName("not-the-host.invalid")
            .addSubjectAlternativeName("not-the-host.invalid")
            .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()

        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverCerts.sslContext())
        server.createContext("/") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        NetworkState.clearForTests()
        stopKoin()
        Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun bypassClient(): OkHttpClient = GlobalContext.get().get(named("insecure"))
    private fun directClient(): OkHttpClient = GlobalContext.get().get(named("direct"))

    /**
     * Addressed by literal rather than by `localhost`, which resolves to both
     * ::1 and 127.0.0.1 on a dual-stack machine: the client would then try two
     * routes and report whichever failure came first, so a certificate verdict
     * could hide behind a connect error. The grant is keyed on this string
     * either way -- NetworkState matches the host as written.
     */
    private fun get(client: OkHttpClient): String =
        client.newCall(Request.Builder().url("https://$HOST:$port/").build())
            .execute().use { it.body.string() }

    @Test
    fun `without a grant the bypass client refuses the certificate`() {
        assertFailsWith<SSLHandshakeException> { get(bypassClient()) }
    }

    @Test
    fun `a grant for this host lets the bypass client through`() {
        NetworkState.grantBypass(HOST, Instant.now().plusSeconds(60))
        assertEquals("ok", get(bypassClient()))
    }

    @Test
    fun `a grant for another host does not relax this one`() {
        // The regression that mattered: one grant, every host relaxed.
        NetworkState.grantBypass("www.smartycraft.ru", Instant.now().plusSeconds(60))
        assertFailsWith<SSLHandshakeException> { get(bypassClient()) }
    }

    @Test
    fun `an expired grant stops relaxing the host`() {
        NetworkState.grantBypass(HOST, Instant.now().minusSeconds(1))
        assertFailsWith<SSLHandshakeException> { get(bypassClient()) }
    }

    @Test
    fun `the direct client never honours a grant`() {
        NetworkState.grantBypass(HOST, Instant.now().plusSeconds(60))
        val failure = assertFailsWith<Exception> { get(directClient()) }
        assertTrue(
            failure is SSLHandshakeException || failure.cause is SSLHandshakeException,
            "direct channel must keep strict TLS whatever the user granted elsewhere, got $failure",
        )
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
