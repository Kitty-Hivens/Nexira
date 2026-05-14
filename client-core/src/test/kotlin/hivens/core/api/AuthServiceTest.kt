package hivens.core.api

import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.StatusOnlyResponse
import hivens.core.data.AuthStatus
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `login throws TwoFactorRequiredException on TWOAUTH (carries uid)`() = runTest {
        // Server returns TWOAUTH with uid populated (the protocol spec
        // shows minimal status-only example but real responses carry uid
        // so the client can sign the twoauth follow-up).
        val ex = assertFailsWith<TwoFactorRequiredException> {
            AuthService(protocol(LoginResponse(status = "TWOAUTH", uid = "abc-uid-128")))
                .login("2fa_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.NEED_2FA, ex.status)
        assertEquals("abc-uid-128", ex.uid)
        assertEquals("2fa_user", ex.login)
    }

    @Test
    fun `login throws TwoFactorRequiredException with null uid when server omits it`() = runTest {
        // Per protocol-spec note: the TWOAUTH response is sometimes status-only.
        // Pass through whatever uid we got; completeTwoFactor handles the
        // missing case explicitly. Important to surface the absence so the
        // UI can decide to retry the full login rather than show a 2FA prompt
        // that can never succeed.
        val ex = assertFailsWith<TwoFactorRequiredException> {
            AuthService(protocol(LoginResponse(status = "TWOAUTH")))
                .login("2fa_user", "pass", "Industrial")
        }
        assertEquals(null, ex.uid)
    }

    // ── completeTwoFactor (#159) ──────────────────────────────────────────

    @Test
    fun `completeTwoFactor returns SessionData when twoauth=OK and second login succeeds`() = runTest {
        // Two login() responses: first TWOAUTH, second OK after twoauth verify.
        val proto = FakeServerProtocol().apply {
            var loginAttempt = 0
            loginResult = {
                loginAttempt += 1
                if (loginAttempt == 1) LoginResponse(status = "TWOAUTH", uid = "abc-uid-128")
                else ok().copy(uid = "abc-uid-128")
            }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = AuthService(proto)
        // First call surfaces the TWOAUTH need.
        val ex = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        // UI prompts for code, threads through uid + originating credentials.
        val session = service.completeTwoFactor(
            username = "user", password = "pass", serverId = "Industrial",
            uid = ex.uid!!, code = "123456",
        )
        assertEquals("TestPlayer", session.playerName)
        assertEquals(AuthStatus.OK, session.status)
        assertEquals(1, proto.twoauthCalls.size, "twoauth should be called exactly once")
        assertEquals("123456", proto.twoauthCalls.single().third)
        assertEquals(2, proto.loginCalls.size, "second login follows twoauth=OK")
    }

    @Test
    fun `completeTwoFactor throws WRONG_CODE when twoauth=CODE`() = runTest {
        val proto = FakeServerProtocol().apply {
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "CODE") }
        }
        val ex = assertFailsWith<AuthException> {
            AuthService(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "abc-uid-128", code = "000000")
        }
        assertEquals(AuthStatus.WRONG_CODE, ex.status)
        assertEquals(0, proto.loginCalls.size, "no second login attempt on wrong code")
    }

    @Test
    fun `completeTwoFactor throws TWO_FACTOR_EXPIRED when twoauth=LOGIN`() = runTest {
        val proto = FakeServerProtocol().apply {
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "LOGIN") }
        }
        val ex = assertFailsWith<AuthException> {
            AuthService(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "abc-uid-128", code = "123456")
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
    }

    @Test
    fun `completeTwoFactor with blank uid fails fast without hitting the network`() = runTest {
        // The TWOAUTH login response sometimes omits uid (server quirk per
        // the protocol spec). The flow can't continue without it — fail
        // immediately with a clear message so the UI can prompt for a full
        // re-login instead of showing a 2FA dialog that can never succeed.
        val proto = FakeServerProtocol()
        val ex = assertFailsWith<AuthException> {
            AuthService(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "", code = "123456")
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
        assertTrue(proto.twoauthCalls.isEmpty(), "fail-fast — no twoauth network call")
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
