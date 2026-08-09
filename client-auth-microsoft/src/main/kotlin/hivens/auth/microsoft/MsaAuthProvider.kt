package hivens.auth.microsoft

import hivens.auth.AuthCapabilities
import hivens.auth.AuthProvider
import hivens.auth.DeviceCodeAuthProvider
import hivens.auth.DeviceCodeChallenge
import hivens.auth.RefreshableAuthProvider
import hivens.core.api.AuthException
import hivens.core.api.HttpClientProvider
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Microsoft (MSA) auth via the OAuth 2.0 device-code grant, then the Xbox Live
 * -> XSTS -> Minecraft-services token exchange + profile fetch.
 *
 * Runs on the direct [HttpClientProvider] (login.microsoftonline.com /
 * *.xboxlive.com / api.minecraftservices.com must be reached under strict TLS,
 * never over the SmartyCraft channel, whose client honours a user-granted SSL
 * bypass -- a Microsoft token must not travel over a connection the user told
 * the launcher to trust unconditionally). Implements [AuthProvider] so the
 * registry/gate see it as a provider, and [DeviceCodeAuthProvider] as the
 * interactive entry point; [login] and [completeTwoFactor] are unsupported --
 * there is no username/password path.
 *
 * Responses are read as text and decoded explicitly (no reliance on the injected
 * client having ContentNegotiation installed); request bodies are sent raw
 * (form-encoded or JSON string), so the provider is engine-agnostic.
 */
class MsaAuthProvider(
    private val httpProvider: HttpClientProvider,
    private val clientId: String,
    private val json: Json = DEFAULT_JSON,
) : AuthProvider, DeviceCodeAuthProvider, RefreshableAuthProvider {

    private val http get() = httpProvider.current

    override val id: String = PROVIDER_KEY
    override val displayName: String = "Microsoft"
    override val capabilities = AuthCapabilities(supports2FA = false, supportsDeviceCode = true)

    override suspend fun login(username: String, password: String, serverId: String): SessionData =
        throw UnsupportedOperationException("Microsoft uses the device-code flow, not username/password")

    override suspend fun completeTwoFactor(
        username: String, password: String, serverId: String, uid: String, code: String,
    ): SessionData = throw UnsupportedOperationException("Microsoft uses the device-code flow")

    override suspend fun requestDeviceCode(): DeviceCodeChallenge {
        val resp = http.post(DEVICE_CODE_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form("client_id" to clientId, "scope" to SCOPE))
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw err("device-code request failed: ${text.take(200)}")
        val dc = json.decodeFromString(DeviceCodeResponse.serializer(), text)
        return DeviceCodeChallenge(
            userCode = dc.userCode,
            verificationUri = dc.verificationUri,
            deviceCode = dc.deviceCode,
            intervalSeconds = dc.interval,
            expiresInSeconds = dc.expiresIn,
        )
    }

    override suspend fun awaitToken(challenge: DeviceCodeChallenge): SessionData {
        var intervalMs = challenge.intervalSeconds.coerceAtLeast(1) * 1000L
        var waitedMs = 0L
        val maxMs = challenge.expiresInSeconds.coerceAtLeast(1) * 1000L
        while (waitedMs < maxMs) {
            delay(intervalMs)            // also the cancellation point: dialog dismiss aborts here
            waitedMs += intervalMs
            val resp = http.post(TOKEN_URL) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form(
                    "grant_type" to DEVICE_CODE_GRANT,
                    "client_id" to clientId,
                    "device_code" to challenge.deviceCode,
                ))
            }
            val text = resp.bodyAsText()
            if (resp.status.isSuccess()) {
                return exchangeToSession(json.decodeFromString(TokenResponse.serializer(), text))
            }
            when (json.decodeFromString(TokenErrorResponse.serializer(), text).error) {
                "authorization_pending" -> continue
                "slow_down" -> intervalMs += 5_000L
                "authorization_declined" -> throw err("sign-in was declined")
                "expired_token" -> throw err("the device code expired -- start again")
                else -> throw err("token poll failed: ${text.take(200)}")
            }
        }
        throw err("the device code expired -- start again")
    }

    /** Silent re-auth from a stored refresh token; re-runs the Xbox/Minecraft exchange. */
    override suspend fun refresh(refreshToken: String): SessionData {
        val resp = http.post(TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "scope" to SCOPE,
            ))
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw err("token refresh failed: ${text.take(200)}")
        return exchangeToSession(json.decodeFromString(TokenResponse.serializer(), text))
    }

    private suspend fun exchangeToSession(token: TokenResponse): SessionData {
        val (xblToken, uhs) = xblAuthenticate(token.accessToken)
        val xstsToken = xstsAuthorize(xblToken)
        val mcToken = minecraftLogin(uhs, xstsToken)
        val profile = minecraftProfile(mcToken)
        return SessionData(
            status = AuthStatus.OK,
            playerName = profile.name,
            uuid = profile.id.replace("-", ""),
            accessToken = mcToken,
            refreshToken = token.refreshToken.ifBlank { null },
            offline = false,
        )
    }

    private suspend fun xblAuthenticate(msaAccessToken: String): Pair<String, String> {
        val resp = http.post(XBL_AUTH_URL) {
            jsonBody(
                """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",""" +
                    """"RpsTicket":"d=$msaAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""",
            )
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw err("Xbox Live authentication failed: ${text.take(200)}")
        val xbl = json.decodeFromString(XblResponse.serializer(), text)
        val uhs = xbl.displayClaims.xui.firstOrNull()?.uhs
            ?: throw err("Xbox Live returned no user hash")
        return xbl.token to uhs
    }

    private suspend fun xstsAuthorize(xblToken: String): String {
        val resp = http.post(XSTS_AUTH_URL) {
            jsonBody(
                """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},""" +
                    """"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}""",
            )
        }
        val text = resp.bodyAsText()
        if (resp.status.value == 401) {
            val xErr = runCatching { json.decodeFromString(XstsErrorResponse.serializer(), text).xErr }.getOrNull()
            throw err(
                when (xErr) {
                    2148916233L -> "this Microsoft account has no Xbox profile -- create one at xbox.com first"
                    2148916235L -> "Xbox Live is not available in this account's region"
                    2148916238L -> "this is a child account; add it to a Family group to play"
                    else -> "Xbox XSTS authorization failed (XErr=$xErr)"
                },
            )
        }
        if (!resp.status.isSuccess()) throw err("Xbox XSTS authorization failed: ${text.take(200)}")
        return json.decodeFromString(XstsResponse.serializer(), text).token
    }

    private suspend fun minecraftLogin(uhs: String, xstsToken: String): String {
        val resp = http.post(MC_LOGIN_URL) {
            jsonBody("""{"identityToken":"XBL3.0 x=$uhs;$xstsToken"}""")
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw err("Minecraft services login failed: ${text.take(200)}")
        return json.decodeFromString(McLoginResponse.serializer(), text).accessToken
    }

    private suspend fun minecraftProfile(mcToken: String): McProfileResponse {
        val resp = http.get(MC_PROFILE_URL) { headers.append("Authorization", "Bearer $mcToken") }
        if (resp.status.value == 404) throw err("this Microsoft account does not own Minecraft: Java Edition")
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw err("Minecraft profile fetch failed: ${text.take(200)}")
        return json.decodeFromString(McProfileResponse.serializer(), text)
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun err(message: String): AuthException =
        AuthException(AuthStatus.INTERNAL_ERROR, "Microsoft sign-in: $message")

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int = 5,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Int = 0,
    )

    @Serializable
    private data class TokenErrorResponse(val error: String = "")

    @Serializable
    private data class XblResponse(
        @SerialName("Token") val token: String,
        @SerialName("DisplayClaims") val displayClaims: DisplayClaims = DisplayClaims(),
    )

    @Serializable
    private data class DisplayClaims(val xui: List<Xui> = emptyList())

    @Serializable
    private data class Xui(val uhs: String = "")

    @Serializable
    private data class XstsResponse(@SerialName("Token") val token: String)

    @Serializable
    private data class XstsErrorResponse(@SerialName("XErr") val xErr: Long = 0)

    @Serializable
    private data class McLoginResponse(@SerialName("access_token") val accessToken: String)

    @Serializable
    private data class McProfileResponse(val id: String, val name: String)

    companion object {
        const val PROVIDER_KEY = "microsoft"

        private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val SCOPE = "XboxLive.signin offline_access"

        // The "consumers" authority is the personal-Microsoft-account tenant for
        // Minecraft/Xbox sign-in; the rest are Microsoft's fixed service endpoints.
        private const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        private const val TOKEN_URL       = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val XBL_AUTH_URL    = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_AUTH_URL   = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MC_LOGIN_URL    = "https://api.minecraftservices.com/authentication/login_with_xbox"
        private const val MC_PROFILE_URL  = "https://api.minecraftservices.com/minecraft/profile"

        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}
