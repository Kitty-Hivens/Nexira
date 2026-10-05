package hivens.auth.smartycraft

import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.StatusOnlyResponse
import hivens.core.data.AuthStatus
import hivens.core.util.HashUtils
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Post-Conduit-Phase-1 SmartyCraftAuthProvider tests use [FakeServerProtocol] instead of
 * MockEngine -- same coverage of the response->[hivens.core.data.SessionData]
 * mapping, status enum routing, and edge cases like AES token decryption,
 * but no Ktor wire-format ceremony.
 *
 * Network-level failures (HTTP 500, malformed JSON, etc.) now belong in
 * [hivens.launcher.protocol.SmartycraftV1ProtocolTest] -- that's where the
 * actual HTTP client lives. Here we test what SmartyCraftAuthProvider DOES with a
 * protocol response, not how the protocol assembles HTTP requests.
 */
class SmartyCraftAuthProviderTest {

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

    /**
     * A session as the server sends it: AES under the key derived from [uid], so the
     * provider can derive a game token from it. A value that does not decrypt now
     * fails the sign-in, which is what it should have done all along.
     */
    private fun sealedSession(uid: String, plain: String = "fake-session-bytes"): String {
        val key = HashUtils.md5(uid + Protocol.AUTH_SALT).take(16)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun protocol(response: LoginResponse) = FakeServerProtocol().apply {
        loginResult = { response }
    }

    @Test
    fun `login returns SessionData on OK response`() = runTest {
        val session = SmartyCraftAuthProvider(protocol(ok())).login("user", "pass", "Industrial")
        assertEquals("TestPlayer", session.playerName)
        assertEquals("550e8400e29b41d4a716446655440000", session.uuid)
        assertEquals(AuthStatus.OK, session.status)
        assertEquals(100, session.balance)
    }

    @Test
    fun `login throws AuthException on PASSWORD status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "PASSWORD")))
                .login("user", "wrongpass", "Industrial")
        }
        assertEquals(AuthStatus.PASSWORD, ex.status)
    }

    @Test
    fun `login throws AuthException with BAD_LOGIN status when server returns LOGIN`() = runTest {
        // Wire status "LOGIN" maps to UX status BAD_LOGIN ("user not found")
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "LOGIN")))
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
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "TWOAUTH", uid = "abc-uid-128")))
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
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "TWOAUTH")))
                .login("2fa_user", "pass", "Industrial")
        }
        assertEquals(null, ex.uid)
    }

    // ── completeTwoFactor ───────────────────────────────────────────────────

    @Test
    fun `completeTwoFactor uses cached TWOAUTH response when complete (no re-login)`() = runTest {
        // Server returned a TWOAUTH login response that ALREADY carried full
        // session fields (uuid + playername + uid). After twoauth=OK the
        // launcher must promote that response directly into a SessionData
        // without bouncing through a second login() -- re-login is the
        // fallback path, not the preferred one (#159 followup).
        val proto = FakeServerProtocol().apply {
            loginResult = {
                LoginResponse(
                    status = "TWOAUTH",
                    uid = "abc-uid-128",
                    uuid = "550e8400e29b41d4a716446655440000",
                    playername = "TestPlayer",
                    session = sealedSession("abc-uid-128"),
                    money = 50,
                )
            }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)
        val ex = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        val session = service.completeTwoFactor(
            username = "user", password = "pass", serverId = "Industrial",
            uid = ex.uid!!, code = "123456",
        )
        assertEquals("TestPlayer", session.playerName)
        assertEquals(AuthStatus.OK, session.status)
        assertEquals(50, session.balance)
        assertEquals(1, proto.twoauthCalls.size)
        assertEquals(1, proto.loginCalls.size,
            "no second login when the cached TWOAUTH response is complete")
    }

    @Test
    fun `completeTwoFactor refuses when the TWOAUTH response carried no session`() = runTest {
        // Measured against the live API: `twoauth` answers with a bare status, and a
        // second `login` mints a new uid that INVALIDATES the one just unlocked --
        // so re-logging in to "obtain" a session destroys the session. When the
        // TWOAUTH response brought none, there is nothing to unlock and the only
        // honest answer is to send the user back through a full login.
        val proto = FakeServerProtocol().apply {
            loginResult = {
                LoginResponse(
                    status = "TWOAUTH", uid = "abc-uid-128",
                    uuid = "550e8400e29b41d4a716446655440000",
                    playername = "TestPlayer",
                    session = null,
                )
            }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)
        val ex = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        val failure = assertFailsWith<AuthException> {
            service.completeTwoFactor(
                username = "user", password = "pass", serverId = "Industrial",
                uid = ex.uid!!, code = "123456",
            )
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, failure.status)
        assertEquals(1, proto.loginCalls.size, "no second login: it would kill the session it means to fetch")
    }

    @Test
    fun `a completed second factor marks the session so nothing refreshes it later`() = runTest {
        val proto = FakeServerProtocol().apply {
            loginResult = {
                LoginResponse(
                    status = "TWOAUTH", uid = "abc-uid-128",
                    uuid = "550e8400e29b41d4a716446655440000",
                    playername = "TestPlayer",
                    session = sealedSession("abc-uid-128"),
                )
            }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)
        val ex = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        val session = service.completeTwoFactor(
            username = "user", password = "pass", serverId = "Industrial",
            uid = ex.uid!!, code = "123456",
        )
        assertEquals("TestPlayer", session.playerName)
        assertTrue(session.twoFactor, "the flag is what stops auto-sync and pre-spawn refresh from logging in again")
        assertEquals(1, proto.loginCalls.size, "the unlocked session is used as-is")
    }

    /**
     * The launch gate signs in again to see whether the account still answers to a
     * second factor, and a session coming back reads as "it does not". Inside the
     * cache window that session is the one the code unlocked, so it has to say so.
     */
    @Test
    fun `a login answered from the cache after a code still carries the second factor`() = runTest {
        val proto = FakeServerProtocol().apply {
            loginResult = {
                LoginResponse(
                    status = "TWOAUTH", uid = "abc-uid-128",
                    uuid = "550e8400e29b41d4a716446655440000",
                    playername = "TestPlayer",
                    session = sealedSession("abc-uid-128"),
                )
            }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)
        val ex = assertFailsWith<TwoFactorRequiredException> { service.login("user", "pass", "Industrial") }
        service.completeTwoFactor(username = "user", password = "pass", serverId = "Industrial", uid = ex.uid!!, code = "123456")

        val again = service.login("user", "pass", "Industrial")

        assertTrue(again.twoFactor, "the cached session is the unlocked one, not evidence the account dropped its factor")
        assertEquals(1, proto.loginCalls.size)
    }

    @Test
    fun `pending TWOAUTH cache is cleared by a fresh login attempt for the same triple`() = runTest {
        // Audit catch on the 22-commit batch: pendingTwoFactor used to grow
        // unbounded if the user canceled the dialog and retried with a
        // different password. The fresh-login invalidation guards that --
        // verify by chaining a TWOAUTH-yielding login, a wrong-password
        // attempt (clears the cache), then a TWOAUTH-yielding login again,
        // and confirming the second TWOAUTH path goes through the cache-promotion
        // fallback (which is only possible if the entry was
        // re-inserted, not pre-existing from the first attempt).
        val responses = mutableListOf(
            LoginResponse(
                status = "TWOAUTH", uid = "first-uid",
                uuid = "550e8400e29b41d4a716446655440000",
                playername = "TestPlayer",
                session = sealedSession("first-uid"),
            ),
            LoginResponse(status = "PASSWORD"),
            LoginResponse(
                status = "TWOAUTH", uid = "second-uid",
                uuid = "550e8400e29b41d4a716446655440000",
                playername = "TestPlayer",
                session = sealedSession("second-uid"),
            ),
        )
        val proto = FakeServerProtocol().apply {
            loginResult = { responses.removeAt(0) }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)

        // 1) First TWOAUTH -- uid is "first-uid". User abandons the dialog.
        val first = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        assertEquals("first-uid", first.uid)

        // 2) User retries with wrong password. PASSWORD branch fires; the
        //    fresh-login invalidation must clear the "first-uid" entry from
        //    pendingTwoFactor BEFORE the network call (otherwise the second
        //    TWOAUTH attempt below would see the stale entry).
        assertFailsWith<AuthException> {
            service.login("user", "wrong-pass", "Industrial")
        }

        // 3) Third TWOAUTH for the original triple -- uid is "second-uid".
        //    Promote-from-cache should pull the SECOND response, not the first.
        val second = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        assertEquals("second-uid", second.uid)
        val session = service.completeTwoFactor(
            username = "user", password = "pass", serverId = "Industrial",
            uid = second.uid!!, code = "111111",
        )
        // Promote-from-cache used the most recent response, not a stale one.
        assertEquals("TestPlayer", session.playerName)
    }

    @Test
    fun `completeTwoFactor breaks the TWOAUTH-on-re-login loop`() = runTest {
        // Server quirk surfaced empirically (2026-05-15): re-login after a
        // verified twoauth=OK still returns TWOAUTH (account doesn't actually
        // have 2FA configured but the server routes through the gate anyway,
        // OR to verify silently failed). Without loop detection the launcher
        // would re-prompt for a code that can never satisfy the server.
        // completeTwoFactor must surface TWO_FACTOR_EXPIRED instead.
        val proto = FakeServerProtocol().apply {
            // Always TWOAUTH with sparse fields.
            loginResult = { LoginResponse(status = "TWOAUTH", uid = "abc-uid-128") }
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "OK") }
        }
        val service = SmartyCraftAuthProvider(proto)
        val firstEx = assertFailsWith<TwoFactorRequiredException> {
            service.login("user", "pass", "Industrial")
        }
        val ex = assertFailsWith<AuthException> {
            service.completeTwoFactor(
                username = "user", password = "pass", serverId = "Industrial",
                uid = firstEx.uid!!, code = "123456",
            )
        }
        // NOT a TwoFactorRequiredException -- the inner re-login loop got
        // converted to a clean restart-the-flow signal.
        assertFalse(ex is TwoFactorRequiredException)
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
    }

    @Test
    fun `completeTwoFactor throws WRONG_CODE when twoauth=CODE`() = runTest {
        val proto = FakeServerProtocol().apply {
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "CODE") }
        }
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(proto).completeTwoFactor("user", "pass", "Industrial",
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
            SmartyCraftAuthProvider(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "abc-uid-128", code = "123456")
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
    }

    @Test
    fun `completeTwoFactor maps generic server ERROR to TWO_FACTOR_EXPIRED (close-dialog UX)`() = runTest {
        // Empirically: the server returns ERROR when twoauth is sent for
        // an account that doesn't actually have 2FA configured (the inverse
        // of the spec's "server sometimes returns OK for accounts WITH 2FA").
        // No code retry can recover; route to the close-dialog UX so the
        // user retries from the credentials form, not from a verify button
        // that will keep returning the same answer.
        val proto = FakeServerProtocol().apply {
            twoauthResult = { _, _, _ -> StatusOnlyResponse(status = "ERROR") }
        }
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "abc-uid-128", code = "123456")
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
    }

    @Test
    fun `completeTwoFactor with blank uid fails fast without hitting the network`() = runTest {
        // The TWOAUTH login response sometimes omits uid (server quirk per
        // the protocol spec). The flow can't continue without it -- fail
        // immediately with a clear message so the UI can prompt for a full
        // re-login instead of showing a 2FA dialog that can never succeed.
        val proto = FakeServerProtocol()
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(proto).completeTwoFactor("user", "pass", "Industrial",
                uid = "", code = "123456")
        }
        assertEquals(AuthStatus.TWO_FACTOR_EXPIRED, ex.status)
        assertTrue(proto.twoauthCalls.isEmpty(), "fail-fast -- no twoauth network call")
    }

    @Test
    fun `login throws AuthException on ACTIVE status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "ACTIVE")))
                .login("inactive_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.ACTIVE, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR for unknown status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(LoginResponse(status = "BANNED")))
                .login("banned_user", "pass", "Industrial")
        }
        // Unknown status -> ProtocolStatus.ERROR -> AuthStatus.INTERNAL_ERROR
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR when OK but uuid is missing`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(ok(uuid = "12345").copy(uuid = null)))
                .login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR when OK but playername is missing`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(ok().copy(playername = null)))
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
            SmartyCraftAuthProvider(proto).login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
        // Network-shaped: the auto-login coordinator retries on this flag;
        // a server-side rejection must never carry it.
        assertTrue(ex.isNetworkError)
    }

    /**
     * A login that timed out reading its answer may have been processed, and for a
     * two-factor account that means a code was sent. Running it again sends a second
     * code and the first one stops working.
     */
    @Test
    fun `a login whose answer timed out is not sent again`() = runTest {
        val proto = FakeServerProtocol().apply {
            loginResult = { throw SocketTimeoutException("Read timed out") }
        }
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(proto).login("user", "pass", "Industrial")
        }
        assertTrue(ex.isNetworkError, "the caller still learns it was the network")
        assertEquals(1, proto.loginCalls.size)
    }

    @Test
    fun `a login that never connected is tried again`() = runTest {
        var calls = 0
        val proto = FakeServerProtocol().apply {
            loginResult = { if (calls++ == 0) throw ConnectException("Connection refused") else ok() }
        }
        val session = SmartyCraftAuthProvider(proto).login("user", "pass", "Industrial")

        assertEquals("TestPlayer", session.playerName)
        assertEquals(2, proto.loginCalls.size)
    }

    @Test
    fun `a code check whose answer timed out is not sent again`() = runTest {
        val proto = FakeServerProtocol().apply {
            twoauthResult = { _, _, _ -> throw SocketTimeoutException("Read timed out") }
        }
        assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(proto).completeTwoFactor("user", "pass", "Industrial", uid = "abc-uid-128", code = "123456")
        }
        assertEquals(1, proto.twoauthCalls.size, "a spent code checked again reads as a wrong one")
    }

    /**
     * The encrypted value used to stand in for the token, silently, and the game
     * then died at the auth host with nothing pointing back at the sign-in.
     */
    @Test
    fun `a session that does not decrypt fails the sign-in instead of becoming the token`() = runTest {
        val ex = assertFailsWith<AuthException> {
            SmartyCraftAuthProvider(protocol(ok(session = "THIS_IS_NOT_VALID_BASE64!!!###")))
                .login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
        assertFalse(ex.isNetworkError, "a broken token scheme is not something to retry")
    }

    @Test
    fun `login maps the clan tag and marks it resolved`() = runTest {
        val session = SmartyCraftAuthProvider(protocol(ok().copy(clan = "ANIME")))
            .login("user", "pass", "Industrial")
        assertEquals("ANIME", session.clan)
        assertTrue(session.clanResolved)
    }

    @Test
    fun `login marks a clan-less account resolved too`() = runTest {
        // clan == null + clanResolved == true is the reliable "definitely no
        // clan" signal the cape gate hides on; only PRE-FIELD persisted
        // sessions carry clanResolved == false (the fail-open case).
        val session = SmartyCraftAuthProvider(protocol(ok())).login("user", "pass", "Industrial")
        assertEquals(null, session.clan)
        assertTrue(session.clanResolved)
    }

    @Test
    fun `login preserves serverId in returned SessionData`() = runTest {
        val session = SmartyCraftAuthProvider(protocol(ok())).login("user", "pass", "Nevermine")
        assertEquals("Nevermine", session.serverId)
    }

    @Test
    fun `login strips dashes from uuid`() = runTest {
        val session = SmartyCraftAuthProvider(protocol(ok(uuid = "550e8400-e29b-41d4-a716-446655440000")))
            .login("user", "pass", "Industrial")
        assertFalse(session.uuid.contains("-"))
        assertEquals(32, session.uuid.length)
    }

    @Test
    fun `cache hit on second login with same credentials skips network`() = runTest {
        val proto = protocol(ok())
        val service = SmartyCraftAuthProvider(proto)
        service.login("user", "pass", "Industrial")
        service.login("user", "pass", "Industrial")
        // Second call hit the cache; protocol invoked exactly once.
        assertEquals(1, proto.loginCalls.size)
    }

    @Test
    fun `cache miss when different password -- different cache key`() = runTest {
        val proto = protocol(ok())
        val service = SmartyCraftAuthProvider(proto)
        service.login("user", "pass1", "Industrial")
        service.login("user", "pass2", "Industrial")
        assertEquals(2, proto.loginCalls.size)
    }

    @Test
    fun `a login for a second world reuses the first world's session`() = runTest {
        // The token is not scoped to the server (live-probed 2026-09-19), so a
        // sign-in for one world and a launch of a pack bound to another must not
        // force a fresh login -- which on a 2FA account would demand another code
        // and kill the session just confirmed. The cache key is the account, not
        // the account-and-world.
        val proto = protocol(ok())
        val service = SmartyCraftAuthProvider(proto)
        service.login("user", "pass", "Industrial")
        service.login("user", "pass", "RPG")
        assertEquals(1, proto.loginCalls.size, "the second world hits the cache, no second login")
    }

    @Test
    fun `a cache hit for a second world carries that world on the session`() = runTest {
        // The token is shared, but the returned session names the world asked for
        // now, because the launch selects the SC authlib by it.
        val proto = protocol(ok())
        val service = SmartyCraftAuthProvider(proto)
        service.login("user", "pass", "Industrial")
        val second = service.login("user", "pass", "RPG")
        assertEquals("RPG", second.serverId)
    }
}
