package hivens.core.api

import hivens.config.AppConfig
import hivens.core.api.interfaces.IAuthService
import hivens.core.data.AuthStatus
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AuthService(
    private val client: HttpClient,
    private val json: Json
) : IAuthService {

    private val logger = LoggerFactory.getLogger(AuthService::class.java)

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

    override fun login(username: String, password: String, serverId: String): SessionData {
        logger.info("Вход через API V3 (сервер: {})...", serverId)

        val passwordEncoded = getMD5(password)
        val clientSessionId = UUID.randomUUID().toString().replace("-", "")
        val is64 = System.getProperty("os.arch").contains("64")

        // Собираем объект запроса
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

        // Сериализуем в строку JSON для отправки в поле формы
        val jsonString = json.encodeToString(requestPayload)

        val response: AuthResponse = try {
            val call = kotlinx.coroutines.runBlocking { // Временно runBlocking, если интерфейс синхронный. Лучше сделать метод suspend!
                client.submitForm(
                    url = AppConfig.AUTH_URL,
                    formParameters = Parameters.build {
                        append("action", "login")
                        append("json", jsonString)
                    }
                )
            }

            // Читаем тело ответа как строку для ручной обработки ошибок
            val rawBody = kotlinx.coroutines.runBlocking { call.body<String>().trim() }

            // Обработка текстовых ошибок сервера
            if (rawBody.contains("Bad login", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "Неверный логин или пароль")
            if (rawBody.contains("User not found", ignoreCase = true)) throw AuthException(AuthStatus.BAD_LOGIN, "Пользователь не найден")

            // Парсим JSON
            json.decodeFromString(rawBody)

        } catch (e: Exception) {
            if (e is AuthException) throw e
            logger.error("Ошибка авторизации", e)
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Ошибка сети: ${e.message}")
        }

        if (response.status != AuthStatus.OK && response.status != AuthStatus.LOGIN) {
            val msg = when (response.status) {
                AuthStatus.BAD_LOGIN -> "Пользователь не найден"
                AuthStatus.PASSWORD -> "Неверный пароль"
                AuthStatus.NEED_2FA -> "Требуется 2FA"
                AuthStatus.BANNED -> "Аккаунт заблокирован"
                else -> "Ошибка сервера: ${response.status}"
            }
            throw AuthException(response.status ?: AuthStatus.INTERNAL_ERROR, msg)
        }

        // Только если статус ОК, проверяем данные профиля
        if (response.uuid == null || response.playername == null) {
            // Если пароль верный (OK), но сервер не прислал профиль — это внутренняя ошибка
            throw AuthException(AuthStatus.INTERNAL_ERROR, "Неполные данные профиля")
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
            val keyHash = getMD5(uid + salt)
            val key = keyHash.take(16)
            val decrypted = decryptAES(sessionV3, key)
            val hash1 = getMD5(decrypted)
            val suffix = if (hash1.length >= 3) hash1.substring(hash1.length - 3) else ""
            getMD5(hash1 + suffix)
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

    private fun getMD5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(input.toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    private fun generateRandomMac(): String {
        val rand = Random()
        val mac = ByteArray(6)
        rand.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 254).toByte()
        return mac.joinToString("-") { "%02X".format(it) }
    }
}
