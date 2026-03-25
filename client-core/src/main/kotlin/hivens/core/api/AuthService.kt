package hivens.core.api

import hivens.config.AppConfig
import hivens.core.api.interfaces.IAuthService
import hivens.core.data.AuthStatus
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
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
        val classPath: String = AppConfig.PROTOCOL_DEFAULT_JAR,
        val rtCheckSum: String = AppConfig.PROTOCOL_DEFAULT_CSUM
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
        logger.info("Login via API V3 (server: {})...", serverId)

        val passwordEncoded = HashUtils.md5(password)
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
            val call = client.submitForm(
                url = AppConfig.AUTH_URL,
                formParameters = Parameters.build {
                    append("action", "login")
                    append("json", jsonString)
                }
            )
            // Reading the response body as a string for manual error handling
            val rawBody = call.body<String>().trim()

            // Handling text server errors
            if (rawBody.contains("Bad login", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "Invalid login or password")
            if (rawBody.contains("User not found", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "User not found")

            // Parse JSON
            json.decodeFromString(rawBody)

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
        )
    }

    private fun generateGameToken(uid: String?, sessionV3: String?): String? {
        if (sessionV3 == null || uid == null) return sessionV3
        return try {
            val salt = AppConfig.AUTH_SALT
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
