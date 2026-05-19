package hivens.core.api

import hivens.config.Protocol
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.LoginRequest
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.ProtocolStatus
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
import hivens.core.util.retryWithBackoff
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Login coordinator + per-server session cache. Delegates the actual
 * network call to [IServerProtocol.login]; this class owns session
 * caching, password hashing, AES game-token generation, and
 * retry-with-backoff.
 *
 * Status-mapping nuance: the server returns [ProtocolStatus]; this
 * class exposes [AuthStatus] (Aura's UX-facing enum, slightly different
 * shape). Mapping lives in [mapStatus] -- keep aligned when adding values.
 */
class AuthService(
    private val protocol: IServerProtocol,
) : IAuthService {

    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * Per-server session cache. The dashboard renders cards by listing
     * servers (one auth), then Play does the launch auth -- historically
     * two consecutive logins for the same serverId within ~3 seconds.
     * Caching deduplicates to one network request when the user clicks
     * Play inside the TTL window.
     *
     * Cache key includes `passwordHash` because otherwise a second
     * login attempt with the WRONG password within the TTL would
     * silently succeed via cache, masking real credential rotation.
     * MD5 (already computed for the auth request) is the hash form;
     * plaintext never enters the key.
     *
     * 30 s TTL: long enough for "open launcher -> pick server -> click
     * Play", short enough that the upstream still considers the session
     * fresh. In-memory only; process restart re-auths.
     */
    private data class CacheKey(val username: String, val passwordHash: String, val serverId: String)
    private data class CachedSession(val session: SessionData, val expiresAt: Long)

    private val sessionCache = java.util.concurrent.ConcurrentHashMap<CacheKey, CachedSession>()
    private val sessionTtlMs = 30_000L

    /**
     * Per-user cache of the [LoginResponse] the server returned alongside
     * a TWOAUTH status -- held until [completeTwoFactor] consumes it (or
     * a fresh login overwrites it). The TWOAUTH response is sometimes
     * status-only (per spec) and sometimes carries enough session fields
     * to short-circuit the post-twoauth re-login loop. Caching here lets
     * [completeTwoFactor] try the no-re-login path first and avoids the
     * loop the server sometimes drops us into when it returns TWOAUTH on
     * every login retry.
     */
    private val pendingTwoFactor = java.util.concurrent.ConcurrentHashMap<CacheKey, LoginResponse>()

    override suspend fun login(username: String, password: String, serverId: String): SessionData {
        val passwordEncoded = HashUtils.md5(password)
        // Drop any stale TWOAUTH state for this triple -- covers the
        // canceled-2FA-dialog and previous-error retry paths. Without
        // this the pendingTwoFactor map grows unbounded.
        pendingTwoFactor.remove(CacheKey(username, passwordEncoded, serverId))
        cachedFor(username, passwordEncoded, serverId)?.let {
            logger.info("Login via API V3 (server: {}) -- cache hit, skipping network", serverId)
            return it
        }
        logger.info("Login via API V3 (server: {})...", serverId)

        val clientSessionId = UUID.randomUUID().toString().replace("-", "")
        val is64 = System.getProperty("os.arch").contains("64")

        val request = LoginRequest(
            login = username,
            password = passwordEncoded,
            server = serverId,
            session = clientSessionId,
            mac = generateRandomMac(),
            osName = System.getProperty("os.name"),
            osBitness = if (is64) 64 else 32,
            javaVersion = System.getProperty("java.version"),
            javaBitness = if (is64) 64 else 32,
            javaHome = System.getProperty("java.home"),
            classPath = Protocol.DEFAULT_JAR,
            rtCheckSum = Protocol.DEFAULT_CSUM,
        )

        val response: LoginResponse = try {
            retryWithBackoff(operation = "auth login", shouldRetry = ::isTransientNetworkError) {
                protocol.login(request)
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            logger.error("Authorization error", e)
            if (e.isSslCertificateError()) {
                throw AuthException(
                    status     = AuthStatus.INTERNAL_ERROR,
                    message    = "SSL certificate error: ${e.message}",
                    isSslError = true,
                )
            }
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: ${e.message}")
        }

        val parsedStatus = response.parsedStatus
        if (parsedStatus == ProtocolStatus.TWOAUTH) {
            // TWOAUTH is not a failure -- it's "do the second factor and
            // call back". Cache the response so completeTwoFactor can
            // build a SessionData directly from it (preferred path) and
            // only fall back to a re-login when the cached fields are
            // too sparse -- avoids the
            // login->TWOAUTH->twoauth=OK->login->TWOAUTH-> loop the
            // server sometimes drops us into.
            pendingTwoFactor[CacheKey(username, passwordEncoded, serverId)] = response
            throw TwoFactorRequiredException(uid = response.uid, login = username)
        }
        if (parsedStatus != ProtocolStatus.OK) {
            val mapped = mapStatus(parsedStatus)
            val msg = when (parsedStatus) {
                ProtocolStatus.LOGIN -> "User not found"
                ProtocolStatus.PASSWORD -> "Invalid password"
                ProtocolStatus.ACTIVE -> "Account is not activated. Check your email."
                ProtocolStatus.VIRTUAL -> "Virtual account"
                ProtocolStatus.SERVER -> "Invalid server"
                else -> "Server error: ${response.status}"
            }
            throw AuthException(mapped, msg)
        }

        if (response.uuid == null || response.playername == null) {
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Incomplete profile data")
        }

        return buildSessionData(response, password, serverId)
            .also { cacheSession(username, passwordEncoded, serverId, it) }
    }

    override suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData {
        if (uid.isBlank()) {
            // TWOAUTH login response didn't include a uid (server quirk:
            // sometimes the response is status-only). Without uid we
            // cannot sign the twoauth request.
            throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA flow can't continue: server didn't return a session id. Please log in again.",
            )
        }
        val twoauthResponse = try {
            retryWithBackoff(operation = "twoauth verify", shouldRetry = ::isTransientNetworkError) {
                protocol.twoauth(uid = uid, login = username, code = code)
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            logger.error("twoauth network error", e)
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: ${e.message}")
        }

        when (twoauthResponse.parsedStatus) {
            ProtocolStatus.OK -> Unit
            ProtocolStatus.CODE -> throw AuthException(AuthStatus.WRONG_CODE, "Wrong 2FA code")
            ProtocolStatus.LOGIN -> throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA session expired. Please log in again.",
            )
            // Anything else (server-side ERROR / INTERNAL / unexpected
            // status) is unrecoverable from the dialog -- there is
            // nothing the user can re-type that will change the answer.
            // Per spec, the documented recovery is "restart full login",
            // which is exactly the contract of TWO_FACTOR_EXPIRED.
            // Surface that so the UI dismisses the dialog and routes
            // back to the credentials form.
            else -> throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA verification could not be completed. Please log in again.",
            )
        }

        // twoauth=OK -- server considers the second factor satisfied.
        // Two reconstruction strategies:
        //   1. The cached TWOAUTH login response is sometimes complete
        //      (uuid + playername + session all populated) -- promote it
        //      to a SessionData directly. Cheaper and avoids strategy 2's
        //      loop hazard.
        //   2. If the cached response is too sparse, fall back to a
        //      single re-login. If THAT returns TWOAUTH again (server
        //      quirk -- observed when the account doesn't actually have
        //      2FA but the server routes through the gate anyway), give
        //      up with TWO_FACTOR_EXPIRED rather than loop.
        val passwordEncoded = HashUtils.md5(password)
        val key = CacheKey(username, passwordEncoded, serverId)
        val cachedResponse = pendingTwoFactor.remove(key)

        // `session` MUST be checked too -- it's the AES-encrypted bytes
        // that become accessToken via generateGameToken. A response with
        // uuid + playername populated but session null would build a
        // SessionData with an empty accessToken; game would die at
        // smartycraft auth-host with no signal back to the launcher.
        if (cachedResponse != null &&
            cachedResponse.uuid != null &&
            cachedResponse.playername != null &&
            cachedResponse.session != null
        ) {
            return buildSessionData(cachedResponse, password, serverId)
                .also { cacheSession(username, passwordEncoded, serverId, it) }
        }

        return try {
            login(username, password, serverId)
        } catch (_: TwoFactorRequiredException) {
            // Re-login STILL returns TWOAUTH after our verified
            // twoauth=OK. Either the server rejected the verify
            // silently or the account is in a weird state. Looping is
            // wrong; surface as a clean restart-the-flow signal.
            throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA verification didn't unlock the session. Please log in again.",
            )
        }
    }

    /**
     * Reconstructs a [SessionData] from an OK-shaped [LoginResponse]
     * without making any network calls. Shared between the cold-login
     * OK path and the post-twoauth promotion path so the field mapping
     * lives in one place. Caller is responsible for the cache write.
     */
    private fun buildSessionData(response: LoginResponse, password: String, serverId: String): SessionData {
        val finalGameToken = generateGameToken(response.uid, response.session)
        val cleanUuid = response.uuid?.replace("-", "") ?: ""
        return SessionData(
            status = AuthStatus.OK,
            playerName = response.playername ?: "",
            uid = response.uid ?: "",
            uuid = cleanUuid,
            accessToken = finalGameToken ?: "",
            fileManifest = response.client,
            serverId = serverId,
            cachedPassword = password,
            balance = response.money,
        )
    }

    /** Map protocol-layer [ProtocolStatus] to UX-layer [AuthStatus]. Keep aligned with [SessionData.status] consumers. */
    private fun mapStatus(status: ProtocolStatus): AuthStatus = when (status) {
        ProtocolStatus.OK -> AuthStatus.OK
        ProtocolStatus.LOGIN -> AuthStatus.BAD_LOGIN
        ProtocolStatus.PASSWORD -> AuthStatus.PASSWORD
        ProtocolStatus.TWOAUTH -> AuthStatus.NEED_2FA
        ProtocolStatus.CODE -> AuthStatus.WRONG_CODE
        ProtocolStatus.ACTIVE -> AuthStatus.ACTIVE
        else -> AuthStatus.INTERNAL_ERROR
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
        // Colon separator to match the wire spec in
        // `docs/dev/smartycraft-v1-protocol.md` and what smrt-deco emits.
        // Server doesn't validate content today; doc/code drift surfaces
        // as a mystery next time someone reads either side.
        val rand = Random()
        val mac = ByteArray(6)
        rand.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 254).toByte()
        return mac.joinToString(":") { "%02X".format(it) }
    }

    /**
     * True for the narrow set of transient network failures we see on
     * the SMARTYcraft channel -- h2 frame resets over SOCKS, raw socket
     * resets during TLS, ktor's wrapped channel-closed exception. NOT
     * true for [AuthException] (those are server-side rejections;
     * retrying just locks the user out faster) or SSL cert errors
     * (those need user opt-in, not silent retry).
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
