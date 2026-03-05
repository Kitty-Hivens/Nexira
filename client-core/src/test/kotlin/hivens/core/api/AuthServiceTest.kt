package hivens.core.api

import hivens.core.data.AuthStatus
import hivens.test.buildErrorClient
import hivens.test.buildMockClient
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AuthServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // region Fixtures

    private fun okResponse(
        playername: String = "TestPlayer",
        uuid: String = "550e8400e29b41d4a716446655440000",
        uid: String = "12345",
        session: String? = null,
        money: Int = 100
    ) = """
        {
            "status": "OK",
            "playername": "$playername",
            "uid": "$uid",
            "uuid": "$uuid",
            "session": ${if (session != null) "\"$session\"" else "null"},
            "money": $money
        }
    """.trimIndent()

    private fun statusResponse(status: String) = """{"status": "$status"}"""

    private fun legacyLoginResponse() = """
        {
            "status": "LOGIN",
            "playername": "LegacyPlayer",
            "uid": "99",
            "uuid": "aaaabbbbccccdddd0000111122223333",
            "session": null,
            "money": 0
        }
    """.trimIndent()

    // endregion

    @Test
    fun `login returns SessionData on OK response`() = runTest {
        val session = AuthService(buildMockClient(okResponse()), json)
            .login("user", "pass", "Industrial")

        assertEquals("TestPlayer", session.playerName)
        assertEquals("550e8400e29b41d4a716446655440000", session.uuid)
        assertEquals(AuthStatus.OK, session.status)
        assertEquals(100, session.balance)
    }

    @Test
    fun `login succeeds with legacy LOGIN status`() = runTest {
        val session = AuthService(buildMockClient(legacyLoginResponse()), json)
            .login("legacy", "pass", "Industrial")

        assertEquals("LegacyPlayer", session.playerName)
        assertEquals(AuthStatus.LOGIN, session.status)
    }

    @Test
    fun `login throws AuthException on PASSWORD status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(statusResponse("PASSWORD")), json)
                .login("user", "wrongpass", "Industrial")
        }
        assertEquals(AuthStatus.PASSWORD, ex.status)
    }

    @Test
    fun `login throws AuthException on BAD_LOGIN status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(statusResponse("BAD_LOGIN")), json)
                .login("unknown", "pass", "Industrial")
        }
        assertEquals(AuthStatus.BAD_LOGIN, ex.status)
    }

    @Test
    fun `login throws AuthException when server returns plain-text Bad login`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(
                buildMockClient(body = "Bad login", contentType = ContentType.Text.Plain),
                json
            ).login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.BAD_LOGIN, ex.status)
    }

    @Test
    fun `login throws AuthException on BANNED status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(statusResponse("BANNED")), json)
                .login("banned_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.BANNED, ex.status)
    }

    @Test
    fun `login throws AuthException on NEED_2FA status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(statusResponse("NEED_2FA")), json)
                .login("2fa_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.NEED_2FA, ex.status)
    }

    @Test
    fun `login throws AuthException on ACTIVE status`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(statusResponse("ACTIVE")), json)
                .login("inactive_user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.ACTIVE, ex.status)
    }

    @Test
    fun `login throws INTERNAL_ERROR when OK but uuid is missing`() = runTest {
        val body = """{"status": "OK", "playername": "Player", "uid": "1", "session": null}"""
        val ex = assertFailsWith<AuthException> {
            AuthService(buildMockClient(body), json)
                .login("user", "pass", "Industrial")
        }
        assertEquals(AuthStatus.INTERNAL_ERROR, ex.status)
    }

    @Test
    fun `login throws AuthException on malformed JSON response`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(
                buildMockClient(
                    body = "<!DOCTYPE html><html>Server Error</html>",
                    contentType = ContentType.Text.Html
                ),
                json
            ).login("user", "pass", "Industrial")
        }
        assertNotNull(ex)
    }

    @Test
    fun `login throws AuthException on HTTP 500`() = runTest {
        val ex = assertFailsWith<AuthException> {
            AuthService(buildErrorClient(HttpStatusCode.InternalServerError), json)
                .login("user", "pass", "Industrial")
        }
        assertNotNull(ex)
    }

    @Test
    fun `login succeeds when AES token decryption fails`() = runTest {
        val body = """
            {
                "status": "OK",
                "playername": "Player",
                "uid": "42",
                "uuid": "aabbccddeeff00112233445566778899",
                "session": "THIS_IS_NOT_VALID_BASE64!!!###",
                "money": 0
            }
        """.trimIndent()
        val session = AuthService(buildMockClient(body), json)
            .login("user", "pass", "Industrial")

        assertEquals("Player", session.playerName)
        assertNotNull(session.accessToken)
    }

    @Test
    fun `login preserves serverId in returned SessionData`() = runTest {
        val session = AuthService(buildMockClient(okResponse()), json)
            .login("user", "pass", "Nevermine")

        assertEquals("Nevermine", session.serverId)
    }

    @Test
    fun `login strips dashes from uuid`() = runTest {
        val session = AuthService(
            buildMockClient(okResponse(uuid = "550e8400-e29b-41d4-a716-446655440000")),
            json
        ).login("user", "pass", "Industrial")

        assertFalse(session.uuid.contains("-"))
        assertEquals(32, session.uuid.length)
    }
}
