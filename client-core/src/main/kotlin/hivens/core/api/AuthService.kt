package hivens.core.api

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import hivens.config.ServiceEndpoints
import hivens.core.api.interfaces.IAuthService
import hivens.core.data.AuthStatus
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AuthService(baseClient: OkHttpClient, private val gson: Gson) : IAuthService {

    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    private val client: OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(ServiceEndpoints.PROXY_HOST, ServiceEndpoints.PROXY_PORT)))
        .proxyAuthenticator { _, response ->
            val credential = Credentials.basic("proxyuser", "proxyuserproxyuser")
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class AuthResponse(
        @SerializedName("status") val status: AuthStatus? = null,
        @SerializedName("playername") val playername: String? = null,
        @SerializedName("uid") val uid: String? = null,
        @SerializedName("uuid") val uuid: String? = null,
        @SerializedName("session") val session: String? = null,
        @SerializedName("client") val client: FileManifest? = null,
        @SerializedName("money") val money: Int = 0
    )

    override fun login(username: String, password: String, serverId: String): SessionData {
        logger.info("Вход через API V3 (сервер: {})...", serverId)

        val passwordEncoded = getMD5(password)
        val clientSessionId = UUID.randomUUID().toString().replace("-", "")
        val is64 = System.getProperty("os.arch").contains("64")

        val payload = mapOf(
            "login" to username,
            "password" to passwordEncoded,
            "server" to serverId,
            "session" to clientSessionId,
            "mac" to generateRandomMac(),
            "osName" to System.getProperty("os.name"),
            "osBitness" to if (is64) 64 else 32,
            "javaVersion" to System.getProperty("java.version"),
            "javaBitness" to if (is64) 64 else 32,
            "javaHome" to System.getProperty("java.home"),
            "classPath" to "smartycraft.jar",
            "rtCheckSum" to "d41d8cd98f00b204e9800998ecf8427e"
        )

        val formBody = FormBody.Builder()
            .add("action", "login")
            .add("json", gson.toJson(payload))
            .build()

        val request = Request.Builder()
            .url(ServiceEndpoints.AUTH_LOGIN)
            .post(formBody)
            .header("User-Agent", "SMARTYlauncher/3.6.2")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AuthException(AuthStatus.INTERNAL_ERROR, "HTTP ${response.code}")
            }

            var rawResponse = response.body?.string()?.trim() ?: ""

            // Серверы часто возвращают "Bad login" как plain text
            if (rawResponse.contains("Bad login", ignoreCase = true)) {
                throw AuthException(AuthStatus.BAD_LOGIN, "Неверный логин или пароль")
            }
            if (rawResponse.contains("User not found", ignoreCase = true)) {
                throw AuthException(AuthStatus.BAD_LOGIN, "Пользователь не найден")
            }

            // Пытаемся найти JSON
            val start = rawResponse.indexOf("{")
            val end = rawResponse.lastIndexOf("}")

            if (start != -1 && end != -1) {
                rawResponse = rawResponse.substring(start, end + 1)
            } else {
                // Если это не JSON и не известная ошибка
                logger.warn("Server returned unknown text: $rawResponse")
                // Выводим сырой ответ, если он короткий, иначе общую ошибку
                val msg = if (rawResponse.length < 50) "Ошибка: $rawResponse" else "Некорректный ответ сервера"
                throw AuthException(AuthStatus.INTERNAL_ERROR, msg)
            }

            try {
                val authResp = gson.fromJson(rawResponse, AuthResponse::class.java)
                    ?: throw AuthException(AuthStatus.INTERNAL_ERROR, "Пустой ответ сервера")

                if (authResp.status != AuthStatus.OK) {
                    val msg = when (authResp.status) {
                        AuthStatus.BAD_LOGIN -> "Неверный логин или пароль"
                        AuthStatus.NEED_2FA -> "Требуется двухфакторная аутентификация"
                        AuthStatus.BANNED -> "Аккаунт заблокирован"
                        null -> "Сервер отклонил вход (причина не указана)"
                        else -> "Ошибка сервера: ${authResp.status}"
                    }
                    throw AuthException(authResp.status ?: AuthStatus.INTERNAL_ERROR, msg)
                }

                if (authResp.uuid == null || authResp.playername == null) {
                    throw AuthException(AuthStatus.INTERNAL_ERROR, "Неполные данные профиля")
                }

                val finalGameToken = generateGameToken(authResp.uid, authResp.session)
                val cleanUuid = authResp.uuid.replace("-", "")

                return SessionData(
                    status = authResp.status,
                    playerName = authResp.playername,
                    uid = authResp.uid ?: "",
                    uuid = cleanUuid,
                    accessToken = finalGameToken ?: "",
                    fileManifest = authResp.client,
                    serverId = serverId,
                    cachedPassword = password,
                    balance = authResp.money
                )

            } catch (e: JsonSyntaxException) {
                logger.error("JSON Error: {}", rawResponse)
                throw AuthException(AuthStatus.INTERNAL_ERROR, "Сбой чтения ответа")
            }
        }
    }

    private fun generateGameToken(uid: String?, sessionV3: String?): String? {
        if (sessionV3 == null || uid == null) return sessionV3
        return try {
            val salt = "sdgsdfhgosd8dfrg"
            val keyHash = getMD5(uid + salt)
            val key = keyHash.substring(0, 16)
            val decrypted = decryptAES(sessionV3, key)
            val hash1 = getMD5(decrypted)
            val suffix = if (hash1.length >= 3) hash1.substring(hash1.length - 3) else ""
            getMD5(hash1 + suffix)
        } catch (e: Exception) {
            logger.error("Failed to generate game token", e)
            sessionV3
        }
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
        } catch (e: Exception) { "" }
    }

    private fun generateRandomMac(): String {
        val rand = Random()
        val mac = ByteArray(6)
        rand.nextBytes(mac)
        mac[0] = (mac[0].toInt() and 254).toByte()
        return mac.joinToString("-") { "%02X".format(it) }
    }
}
