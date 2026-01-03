package hivens.core.api

import hivens.config.AppConfig
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.SessionData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

class SkinRepository(
    private val client: HttpClient,
    private val json: Json // Инжектим Json для ручной сериализации payload
) {
    private val logger = LoggerFactory.getLogger("SkinRepository")

    suspend fun uploadSkin(file: File, isCloak: Boolean, session: SessionData): String {
        val type = if (isCloak) "cloak" else "skin"
        val action = if (isCloak) "cloakupload" else "skinupload"
        
        // 1. Подготовка JSON payload (login)
        val jsonPayload = """{"login":"${session.playerName}"}"""

        // 2. Генерация подписи (MD5)
        // Логика из SmartyNetworkService: MD5( (time/10) + "|" + uid + "|" + login )
        val timestamp = System.currentTimeMillis() / 1000L / 10L
        val signString = "$timestamp|${session.uid}|${session.playerName}"
        val checkHash = getMD5(signString)

        return try {
            val response = client.post(AppConfig.AUTH_URL) {
                setBody(MultiPartFormDataContent(
                    formData {
                        append("action", action)
                        append("json", jsonPayload)
                        append("check", checkHash)
                        append(type, file.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, "image/png")
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        })
                    }
                ))
            }

            // Обработка ответа. Сервер может вернуть JSON или просто текст.
            val bodyText = response.body<String>().trim()
            
            // Попытка распарсить как JSON
            if (bodyText.startsWith("{")) {
                try {
                    val respObj = json.decodeFromString<SmartyResponse>(bodyText)
                    if (respObj.status == "OK") return "OK"
                    return mapErrorStatus(respObj.status)
                } catch (_: Exception) {}
            }

            if (bodyText.contains("OK")) return "OK"

            return bodyText
            
        } catch (e: Exception) {
            logger.error("Ошибка загрузки ассета", e)
            "Ошибка соединения: ${e.message}"
        }
    }

    private fun mapErrorStatus(status: String?): String {
        return when (status) {
            "SIZE" -> "Ошибка: Неверный размер (нужен 64x32/64x64)"
            "TYPE" -> "Ошибка: Неверный формат файла"
            "HD" -> "Ошибка: HD скины доступны только премиум аккаунтам"
            else -> "Ошибка сервера: $status"
        }
    }

    private fun getMD5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}