package hivens.auth.smartycraft

import hivens.auth.AbstractCachingAuthProvider
import hivens.auth.AuthCapabilities
import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.LoginRequest
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.ProtocolStatus
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * SmartyCraft V1 [hivens.auth.AuthProvider]. Delegates the network call to
 * [IServerProtocol]; owns the SmartyCraft-specific pieces: the AES game-token
 * derived from `AUTH_SALT`, the V1 [LoginRequest] shape, the
 * [ProtocolStatus] -> [AuthStatus] mapping, and the TWOAUTH/2FA flow. The
 * provider-agnostic session cache + retry funnel live in
 * [AbstractCachingAuthProvider].
 */
class SmartyCraftAuthProvider(
    private val protocol: IServerProtocol,
) : AbstractCachingAuthProvider() {

    override val id = "smartycraft"
    override val displayName = "SmartyCraft"
    // The second factor works; what did not was logging in again afterwards.
    // See completeTwoFactor: SmartyCraft mints a new uid per login and kills the
    // previous one, so the code has to unlock the session already in hand.
    override val capabilities = AuthCapabilities(supports2FA = true)

    /**
     * Per-user cache of the [LoginResponse] returned alongside a TWOAUTH status,
     * held until [completeTwoFactor] consumes it. The TWOAUTH response sometimes
     * carries enough session fields to skip the post-twoauth re-login loop the
     * server otherwise drops us into.
     */
    private val pendingTwoFactor = ConcurrentHashMap<CacheKey, LoginResponse>()

    override suspend fun login(username: String, password: String, serverId: String): SessionData {
        val passwordEncoded = HashUtils.md5(password)
        val key = CacheKey(username, passwordEncoded, serverId)
        // Drop any stale TWOAUTH state -- covers canceled-2FA-dialog and
        // previous-error retry paths; otherwise pendingTwoFactor grows unbounded.
        pendingTwoFactor.remove(key)
        cachedSession(key)?.let {
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

        val response = withRetry("auth login") { protocol.login(request) }

        val parsedStatus = response.parsedStatus
        if (parsedStatus == ProtocolStatus.TWOAUTH) {
            // TWOAUTH is not a failure -- it's "do the second factor and call
            // back". Cache the response so completeTwoFactor can build a
            // SessionData directly (preferred path) and only re-login when the
            // cached fields are too sparse.
            pendingTwoFactor[key] = response
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
            .also { cacheSession(key, it) }
    }

    override suspend fun completeTwoFactor(
        username: String,
        password: String,
        serverId: String,
        uid: String,
        code: String,
    ): SessionData {
        if (uid.isBlank()) {
            // TWOAUTH response didn't include a uid (server quirk: sometimes
            // status-only). Without uid we cannot sign the twoauth request.
            throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA flow can't continue: server didn't return a session id. Please log in again.",
            )
        }
        val twoauthResponse = withRetry("twoauth verify") {
            protocol.twoauth(uid = uid, login = username, code = code)
        }

        when (twoauthResponse.parsedStatus) {
            ProtocolStatus.OK -> Unit
            ProtocolStatus.CODE -> throw AuthException(AuthStatus.WRONG_CODE, "Wrong 2FA code")
            ProtocolStatus.LOGIN -> throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA session expired. Please log in again.",
            )
            // Anything else (server-side ERROR / INTERNAL / unexpected status)
            // is unrecoverable from the dialog -- the documented recovery is
            // "restart full login", which is exactly TWO_FACTOR_EXPIRED.
            else -> throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "2FA verification could not be completed. Please log in again.",
            )
        }

        // twoauth=OK unlocks the session that CAME WITH the TWOAUTH response --
        // it returns a bare status and no session of its own. Logging in again to
        // "get" one is what used to happen here and it cannot work: a second login
        // mints a new uid, invalidates the one just unlocked, and answers TWOAUTH
        // again, so the user is asked for code after code while every confirmed
        // session dies behind them. Measured against the live API, not guessed.
        val passwordEncoded = HashUtils.md5(password)
        val key = CacheKey(username, passwordEncoded, serverId)
        val cachedResponse = pendingTwoFactor.remove(key)

        // `session` MUST be checked too -- it's the AES bytes that become
        // accessToken. uuid+playername populated but session null would build a
        // SessionData with an empty accessToken; the game dies at the auth host
        // with no signal back.
        if (cachedResponse == null ||
            cachedResponse.uuid == null ||
            cachedResponse.playername == null ||
            cachedResponse.session == null
        ) {
            // Nothing to unlock: the demand arrived without a session (or the
            // dialog outlived it). Restarting the whole login is the only way
            // forward, and saying so beats silently re-logging in.
            throw AuthException(
                AuthStatus.TWO_FACTOR_EXPIRED,
                "The 2FA session expired before the code arrived. Please log in again.",
            )
        }
        return buildSessionData(cachedResponse, password, serverId)
            .copy(twoFactor = true)
            .also { cacheSession(key, it) }
    }

    /**
     * Reconstructs a [SessionData] from an OK-shaped [LoginResponse] with no
     * network call. Shared between the cold-login OK path and the post-twoauth
     * promotion path. Caller writes the cache.
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
            clan = response.clan,
            clanResolved = true,
        )
    }

    /** Map protocol-layer [ProtocolStatus] to UX-layer [AuthStatus]. */
    private fun mapStatus(status: ProtocolStatus): AuthStatus = when (status) {
        ProtocolStatus.OK -> AuthStatus.OK
        ProtocolStatus.LOGIN -> AuthStatus.BAD_LOGIN
        ProtocolStatus.PASSWORD -> AuthStatus.PASSWORD
        ProtocolStatus.TWOAUTH -> AuthStatus.NEED_2FA
        ProtocolStatus.CODE -> AuthStatus.WRONG_CODE
        ProtocolStatus.ACTIVE -> AuthStatus.ACTIVE
        else -> AuthStatus.INTERNAL_ERROR
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
        // `docs/dev/smartycraft-v1-protocol.md`. Server doesn't validate the
        // content today; doc/code drift surfaces as a mystery later.
        val rand = Random()
        val mac = ByteArray(6)
        rand.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 254).toByte()
        return mac.joinToString(":") { "%02X".format(it) }
    }
}
