package hivens.auth.microsoft

import hivens.core.api.AuthException
import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MsaAuthProviderTest {

    private val deviceJson =
        """{"device_code":"DEV","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/link","expires_in":900,"interval":1}"""
    private val tokenJson =
        """{"access_token":"MSA_AT","refresh_token":"MSA_RT","expires_in":3600}"""
    private val xblJson = """{"Token":"XBL_TOK","DisplayClaims":{"xui":[{"uhs":"USERHASH"}]}}"""
    private val xstsJson = """{"Token":"XSTS_TOK","DisplayClaims":{"xui":[{"uhs":"USERHASH"}]}}"""
    private val mcJson = """{"access_token":"MC_TOKEN","token_type":"Bearer","expires_in":86400}"""
    private val profileJson = """{"id":"11112222333344445555666677778888","name":"TestGamer"}"""

    private fun json(body: String, status: HttpStatusCode) =
        Pair(ByteReadChannel(body.toByteArray()), status)

    private fun provider(engine: MockEngine) =
        MsaAuthProvider(HttpClientProvider { HttpClient(engine) }, clientId = "test-client")

    /** Routes every MSA endpoint to a happy response; [tokenError] (if set) is returned
     *  for the FIRST token poll, success afterward. */
    private fun happyEngine(tokenError: String? = null): MockEngine {
        var tokenPolls = 0
        return MockEngine { req ->
            val (channel, status) = when (req.url.encodedPath) {
                "/consumers/oauth2/v2.0/devicecode" -> json(deviceJson, HttpStatusCode.OK)
                "/consumers/oauth2/v2.0/token" -> {
                    tokenPolls++
                    if (tokenError != null && tokenPolls == 1) json("""{"error":"$tokenError"}""", HttpStatusCode.BadRequest)
                    else json(tokenJson, HttpStatusCode.OK)
                }
                "/user/authenticate" -> json(xblJson, HttpStatusCode.OK)
                "/xsts/authorize" -> json(xstsJson, HttpStatusCode.OK)
                "/authentication/login_with_xbox" -> json(mcJson, HttpStatusCode.OK)
                "/minecraft/profile" -> json(profileJson, HttpStatusCode.OK)
                else -> json("not found", HttpStatusCode.NotFound)
            }
            respond(channel, status, headersOf("Content-Type", "application/json"))
        }
    }

    @Test
    fun `device code request returns the user code and verification url`() = runTest {
        val challenge = provider(happyEngine()).requestDeviceCode()
        assertEquals("ABCD-EFGH", challenge.userCode)
        assertEquals("https://microsoft.com/link", challenge.verificationUri)
        assertEquals("DEV", challenge.deviceCode)
        assertEquals(1, challenge.intervalSeconds)
    }

    @Test
    fun `happy path yields a Minecraft session with refresh token`() = runTest {
        val p = provider(happyEngine())
        val session = p.awaitToken(p.requestDeviceCode())
        assertEquals("TestGamer", session.playerName)
        assertEquals("11112222333344445555666677778888", session.uuid)
        assertEquals("MC_TOKEN", session.accessToken)
        assertEquals("MSA_RT", session.refreshToken)
        assertFalse(session.offline)
    }

    @Test
    fun `authorization_pending is polled through to success`() = runTest {
        val p = provider(happyEngine(tokenError = "authorization_pending"))
        assertEquals("TestGamer", p.awaitToken(p.requestDeviceCode()).playerName)
    }

    @Test
    fun `slow_down is honored then completes`() = runTest {
        val p = provider(happyEngine(tokenError = "slow_down"))
        assertEquals("TestGamer", p.awaitToken(p.requestDeviceCode()).playerName)
    }

    @Test
    fun `expired token fails`() = runTest {
        val engine = MockEngine { req ->
            val (channel, status) = when (req.url.encodedPath) {
                "/consumers/oauth2/v2.0/devicecode" -> json(deviceJson, HttpStatusCode.OK)
                "/consumers/oauth2/v2.0/token" -> json("""{"error":"expired_token"}""", HttpStatusCode.BadRequest)
                else -> json("nf", HttpStatusCode.NotFound)
            }
            respond(channel, status, headersOf("Content-Type", "application/json"))
        }
        val p = provider(engine)
        assertFailsWith<AuthException> { p.awaitToken(p.requestDeviceCode()) }
    }

    @Test
    fun `XSTS no-Xbox-account fails with a clear message`() = runTest {
        val engine = MockEngine { req ->
            val (channel, status) = when (req.url.encodedPath) {
                "/consumers/oauth2/v2.0/devicecode" -> json(deviceJson, HttpStatusCode.OK)
                "/consumers/oauth2/v2.0/token" -> json(tokenJson, HttpStatusCode.OK)
                "/user/authenticate" -> json(xblJson, HttpStatusCode.OK)
                "/xsts/authorize" -> json("""{"XErr":2148916233}""", HttpStatusCode.Unauthorized)
                else -> json("nf", HttpStatusCode.NotFound)
            }
            respond(channel, status, headersOf("Content-Type", "application/json"))
        }
        val p = provider(engine)
        val ex = assertFailsWith<AuthException> { p.awaitToken(p.requestDeviceCode()) }
        assertEquals(true, ex.message?.contains("Xbox", ignoreCase = true))
    }

    @Test
    fun `no Minecraft entitlement fails`() = runTest {
        val engine = MockEngine { req ->
            val (channel, status) = when (req.url.encodedPath) {
                "/consumers/oauth2/v2.0/devicecode" -> json(deviceJson, HttpStatusCode.OK)
                "/consumers/oauth2/v2.0/token" -> json(tokenJson, HttpStatusCode.OK)
                "/user/authenticate" -> json(xblJson, HttpStatusCode.OK)
                "/xsts/authorize" -> json(xstsJson, HttpStatusCode.OK)
                "/authentication/login_with_xbox" -> json(mcJson, HttpStatusCode.OK)
                "/minecraft/profile" -> json("", HttpStatusCode.NotFound)
                else -> json("nf", HttpStatusCode.NotFound)
            }
            respond(channel, status, headersOf("Content-Type", "application/json"))
        }
        val p = provider(engine)
        assertFailsWith<AuthException> { p.awaitToken(p.requestDeviceCode()) }
    }

    @Test
    fun `refresh re-runs the exchange and returns a session`() = runTest {
        val session = provider(happyEngine()).refresh("OLD_RT")
        assertEquals("TestGamer", session.playerName)
        assertEquals("MC_TOKEN", session.accessToken)
    }

    @Test
    fun `login and completeTwoFactor are unsupported`() = runTest {
        val p = provider(happyEngine())
        assertFailsWith<UnsupportedOperationException> { p.login("u", "p", "s") }
        assertFailsWith<UnsupportedOperationException> { p.completeTwoFactor("u", "p", "s", "uid", "000000") }
    }
}
