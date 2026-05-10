package hivens.core.api

import hivens.config.Network
import hivens.config.Protocol
import hivens.core.api.interfaces.IAuthService
import hivens.core.data.AuthStatus
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
import hivens.core.util.retryWithBackoff
import io.ktor.client.call.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AuthService(
    private val clientProvider: HttpClientProvider,
    private val json: Json
) : IAuthService {

    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val client get() = clientProvider.current

    /**
     * Per-server session cache. The dashboard renders server cards by
     * doing a "list servers" auth, then the Play button does a "launch
     * server" auth — historically two consecutive logins for the same
     * serverId within ~3 seconds. Both call sites are legitimate; they
     * just don't share state. Caching here deduplicates them down to
     * one network request as long as the user clicks Play within the
     * TTL window.
     *
     * Cache key is `(username, passwordHash, serverId)`. Password hash
     * is mandatory in the key because otherwise a second login attempt
     * with the *wrong* password within the 30 s TTL would silently
     * succeed via cache, masking real credential rotation (Codex P2 on
     * PR #128). We use the MD5 already computed for the auth request,
     * not the plaintext password — same secret-handling profile as the
     * rest of the call.
     *
     * 30 s TTL: long enough for "open launcher → pick server → click Play",
     * short enough that the upstream server still considers the session
     * fresh. Cache is in-memory only — process restart re-auths.
     */
    private data class CacheKey(val username: String, val passwordHash: String, val serverId: String)
    private data class CachedSession(val session: SessionData, val expiresAt: Long)

    private val sessionCache = java.util.concurrent.ConcurrentHashMap<CacheKey, CachedSession>()
    private val sessionTtlMs = 30_000L

    @Serializable
    private data class AuthRequest(
        val login: String,
        val password: String, // MD5 hash
        val server: String,
        val session: String,
        val mac: String,
        val osName: String,
        val osBitness: Int,
        val javaVersion: String,
        val javaBitness: Int,
        val javaHome: String,
        val classPath: String = Protocol.DEFAULT_JAR,
        val rtCheckSum: String = Protocol.DEFAULT_CSUM
    )

    @Serializable
    private data class AuthResponse(
        @SerialName("status") val status: AuthStatus? = null,
        @SerialName("playername") val playername: String? = null,
        @SerialName("uid") val uid: String? = null,
        @SerialName("uuid") val uuid: String? = null,
        @SerialName("session") val session: String? = null,
        @SerialName("client") val client: FileManifest? = null,
        @SerialName("money") val money: Int = 0
    )

    override suspend fun login(username: String, password: String, serverId: String): SessionData {
        // Hash first so the cache key includes the password — otherwise a
        // wrong/rotated password within the TTL would silently succeed
        // via cache. (Codex P2 on PR #128.)
        val passwordEncoded = HashUtils.md5(password)
        cachedFor(username, passwordEncoded, serverId)?.let {
            logger.info("Login via API V3 (server: {}) — cache hit, skipping network", serverId)
            return it
        }
        logger.info("Login via API V3 (server: {})...", serverId)

        val clientSessionId = UUID.randomUUID().toString().replace("-", "")
        val is64 = System.getProperty("os.arch").contains("64")

        // Building request object
        val requestPayload = AuthRequest(
            login = username,
            password = passwordEncoded,
            server = serverId,
            session = clientSessionId,
            mac = generateRandomMac(),
            osName = System.getProperty("os.name"),
            osBitness = if (is64) 64 else 32,
            javaVersion = System.getProperty("java.version"),
            javaBitness = if (is64) 64 else 32,
            javaHome = System.getProperty("java.home")
        )

        // Serialize to JSON string
        val jsonString = json.encodeToString(requestPayload)

        val response: AuthResponse = try {
            retryWithBackoff(operation = "auth login", shouldRetry = ::isTransientNetworkError) {
                val call = client.submitForm(
                    url = Network.AUTH_URL,
                    formParameters = Parameters.build {
                        append("action", "login")
                        append("json", jsonString)
                    }
                )
                // Reading the response body as a string for manual error handling
                val rawBody = call.body<String>().trim()

                // Handling text server errors. These are deliberate auth
                // rejections from the server, never retryable; isTransientNetworkError
                // returns false for AuthException so retryWithBackoff bubbles them
                // out on the first attempt.
                if (rawBody.contains("Bad login", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "Invalid login or password")
                if (rawBody.contains("User not found", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "User not found")

                // Parse JSON
                json.decodeFromString<AuthResponse>(rawBody)
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            logger.error("Authorization error", e)
            if (e.isSslCertificateError()) {
                throw AuthException(
                    status     = AuthStatus.INTERNAL_ERROR,
                    message    = "SSL certificate error: ${e.message}",
                    isSslError = true
                )
            }
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: ${e.message}")
        }

        // Status check logic
        if (response.status != AuthStatus.OK && response.status != AuthStatus.LOGIN) {
            val msg = when (response.status) {
                AuthStatus.BAD_LOGIN -> "User not found"
                AuthStatus.PASSWORD -> "Invalid password"
                AuthStatus.NEED_2FA -> "2FA required"
                AuthStatus.BANNED -> "Account blocked"
                AuthStatus.ACTIVE -> "Account is not activated. Check your email."
                else -> "Server error: ${response.status}"
            }
            throw AuthException(response.status ?: AuthStatus.INTERNAL_ERROR, msg)
        }

        // Only if the status is OK, check the profile data
        if (response.uuid == null || response.playername == null) {
            // If the password is correct (OK), but the server did not send the profile, this is an internal error
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Incomplete profile data")
        }

        val finalGameToken = generateGameToken(response.uid, response.session)
        val cleanUuid = response.uuid.replace("-", "")

        return SessionData(
            status = response.status,
            playerName = response.playername,
            uid = response.uid ?: "",
            uuid = cleanUuid,
            accessToken = finalGameToken ?: "",
            fileManifest = response.client,
            serverId = serverId,
            cachedPassword = password,
            balance = response.money
        ).also { cacheSession(username, passwordEncoded, serverId, it) }
    }

    private fun cachedFor(username: String, passwordHash: String, serverId: String): SessionData? {
        val key = CacheKey(username, passwordHash, serverId)
        val cached = sessionCache[key] ?: return null
        if (System.currentTimeMillis() >= cached.expiresAt) {
            sessionCache.remove(key, cached)
            return null
        }
        return cached.session
    }

    private fun cacheSession(username: String, passwordHash: String, serverId: String, session: SessionData) {
        sessionCache[CacheKey(username, passwordHash, serverId)] =
            CachedSession(session, System.currentTimeMillis() + sessionTtlMs)
    }

    private fun generateGameToken(uid: String?, sessionV3: String?): String? {
        if (sessionV3 == null || uid == null) return sessionV3
        return try {
            val salt = Protocol.AUTH_SALT
            val keyHash = HashUtils.md5(uid + salt)
            val key = keyHash.take(16)
            val decrypted = decryptAES(sessionV3, key)
            val hash1 = HashUtils.md5(decrypted)
            val suffix = if (hash1.length >= 3) hash1.substring(hash1.length - 3) else ""
            HashUtils.md5(hash1 + suffix)
        } catch (_: Exception) { sessionV3 }
    }

    private fun decryptAES(base64Cipher: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        val decodedBytes = Base64.getDecoder().decode(base64Cipher)
        val decryptedBytes = cipher.doFinal(decodedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun generateRandomMac(): String {
        val rand = Random()
        val mac = ByteArray(6)
        rand.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 254).toByte()
        return mac.joinToString("-") { "%02X".format(it) }
    }

    /**
     * True for the narrow set of transient network failures we've seen on
     * the SMARTYcraft channel — h2 frame resets over SOCKS, raw socket
     * resets during TLS, ktor's wrapped channel-closed exception. NOT true
     * for [AuthException] (those are server-side rejections, retrying just
     * locks the user out faster) or SSL cert errors (those need user opt-in,
     * not a silent retry).
     */
    private fun isTransientNetworkError(t: Throwable): Boolean {
        if (t is AuthException) return false
        if (t.isSslCertificateError()) return false
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is java.net.ConnectException ||
                cause is java.net.SocketException ||
                cause is io.ktor.utils.io.ClosedByteChannelException ||
                cause is java.net.SocketTimeoutException
            ) return true
            if (cause is java.io.IOException &&
                cause.message?.contains("Connection reset", ignoreCase = true) == true
            ) return true
            cause = cause.cause
        }
        return false
    }

    private fun Throwable.isSslCertificateError(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is javax.net.ssl.SSLHandshakeException ||
                cause is java.security.cert.CertPathValidatorException ||
                cause.message?.contains("certificate_expired") == true ||
                cause.message?.contains("CertPathValidatorException") == true
            ) return true
            cause = cause.cause
        }
        return false
    }
}
