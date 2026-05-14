package hivens.core.api

import hivens.core.api.protocol.LoginResponse
import hivens.core.data.AuthStatus
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Post-Conduit-Phase-1 AuthService tests use [FakeServerProtocol] instead of
 * MockEngine — same coverage of the response→[hivens.core.data.SessionData]
 * mapping, status enum routing, and edge cases like AES token decryption,
 * but no Ktor wire-format ceremony.
 *
 * Network-level failures (HTTP 500, malformed JSON, etc.) now belong in
 * [hivens.launcher.protocol.SmartycraftV1ProtocolTest] — that's where the
 * actual HTTP client lives. Here we test what AuthService DOES with a
 * protocol response, not how the protocol assembles HTTP requests.
 */
class AuthServiceTest {

    private fun ok(
        playername: String = "TestPlayer",
        uuid: String = "550e8400e29b41d4a716446655440000",
        uid: String = "12345",
        session: String? = null,
        money: Int = 100,
    ) = LoginResponse(
        status = "OK",
        playername = playername,
        uid = uid,
        uuid = uuid,
        session = session,
        money = money,
    )

    private fun protocol(response: LoginResponse) = FakeServerProtocol().apply {
        loginResult = { response }
    }

    @Test
    fun `login returns SessionData on OK response`() = runTest {
        val session = AuthService(protocol(ok())).login("user", "pass", "Industrial")
        assertEquals("TestPlayer", session.playerName)
        assertEquals("550e8400e29b41d4a716446655440000", session.uuid)
        assertEquals(AuthStatus.OK, session.status)
        assertEquals(100, session.balance)
    }

    @Test
    fun `login throws AuthException on PASSWORD status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(LoginResponse(status = "PASSWORD")))
                .login("user", "wrongpass", "Industrial")
        }
        assertEquals(AuthStatus.PASSWORD, ex.status)
    }

    @Test
    fun `login throws AuthException with BAD_LOGIN status when server returns LOGIN`() = runTest {
        // Wire status "LOGIN" maps to UX status BAD_LOGIN ("user not found")
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(LoginResponse(status = "LOGIN")))
                .login("unknown", "pass", "Industrial")
        }
        assertEquals(AuthStatus.BAD_LOGIN, ex.status)
    }

    @Test
    fun `login throws AuthException with NEED_2FA status on TWOAUTH`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(LoginResponse(status = "TWOAUTH")))
                .login("2fa_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.NEED_2FA, ex.status)
    }

    @Test
    fun `login throws AuthException on ACTIVE status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(LoginResponse(status = "ACTIVE")))
                .login("inactive_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.ACTIVE, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR for unknown status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(LoginResponse(status = "BANNED")))
                .login("banned_user", "pass", "Industrial")
        }
        // Unknown status → ProtocolStatus.ERROR → AuthStatus.INTERNAL_ERROR
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR when OK but uuid is missing`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(ok(uuid = "12345").copy(uuid = null)))
                .login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR when OK but playername is missing`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(protocol(ok().copy(playername = null)))
                .login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login translates protocol IOException to INTERNAL_ERROR AuthException`() = runTest {
        val proto = FakeServerProtocol().apply {
            loginResult = { throw java.io.IOException("connection reset") }
        }
        val ex = assertFailsWith<AuthException> {
            AuthService(proto).login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login succeeds when AES token decryption fails (degrades to raw token)`() = runTest {
        val session = AuthService(protocol(ok(session = "THIS_IS_NOT_VALID_BASE64!!!###")))
            .login("user", "pass", "Industrial")
        assertEquals("TestPlayer", session.playerName)
        assertNotNull(session.accessToken)
    }

    @Test
    fun `login preserves serverId in returned SessionData`() = runTest {
        val session = AuthService(protocol(ok())).login("user", "pass", "Nevermine")
        assertEquals("Nevermine", session.serverId)
    }

    @Test
    fun `login strips dashes from uuid`() = runTest {
        val session = AuthService(protocol(ok(uuid = "550e8400-e29b-41d4-a716-446655440000")))
            .login("user", "pass", "Industrial")
        assertFalse(session.uuid.contains("-"))
        assertEquals(32, session.uuid.length)
    }

    @Test
    fun `cache hit on second login with same credentials skips network`() = runTest {
        val proto = protocol(ok())
        val service = AuthService(proto)
        service.login("user", "pass", "Industrial")
        service.login("user", "pass", "Industrial")
        // Second call hit the cache; protocol invoked exactly once.
        assertEquals(1, proto.loginCalls.size)
    }

    @Test
    fun `cache miss when different password — different cache key`() = runTest {
        val proto = protocol(ok())
        val service = AuthService(proto)
        service.login("user", "pass1", "Industrial")
        service.login("user", "pass2", "Industrial")
        assertEquals(2, proto.loginCalls.size)
    }
}
