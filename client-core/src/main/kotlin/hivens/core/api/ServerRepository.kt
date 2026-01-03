package hivens.core.api

import hivens.config.AppConfig
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.DashboardRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

class ServerRepository(
    private val client: HttpClient,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger("ServerRepository")
    private val cachedHashFile = File(AppConfig.FILES_HASH_CACHE)
    // Устаревший хеш, который был зашит в коде
    private var currentHash = AppConfig.DEFAULT_LAUNCHER_HASH

    init {
        if (cachedHashFile.exists()) {
            runCatching {
                val cached = cachedHashFile.readText().trim()
                if (cached.isNotBlank()) currentHash = cached
            }
        }
    }

    /**
     * Получает данные дашборда. Если сервер просит обновление (UPDATE) — 
     * скачивает JAR, обновляет хеш и повторяет запрос.
     */
    suspend fun fetchDashboard(): SmartyResponse {
        var response = requestDashboard(currentHash)

        if (response.status == "UPDATE") {
            logger.info("Статус UPDATE. Начинаем обновление хеша...")
            val newHash = updateLauncherHash()
            if (newHash != null) {
                currentHash = newHash
                saveHash(newHash)
                response = requestDashboard(newHash)
            } else {
                logger.error("Не удалось обновить лаунчер. Возвращаем как есть.")
            }
        }
        return response
    }

    private suspend fun requestDashboard(hash: String): SmartyResponse {
        // Формируем JSON вручную, так как сервер ожидает строку JSON внутри поля формы "json"
        // Это специфика старого PHP бэкенда SmartyCraft
        val requestPayload = DashboardRequest(
            version = AppConfig.LAUNCHER_VERSION,
            cheksum = hash
        )

        val payload = json.encodeToString(requestPayload)

        return try {
            val response = client.post(AppConfig.AUTH_URL) {
                setBody(FormDataContent(Parameters.build {
                    append("action", "loader")
                    append("json", payload)
                }))
            }
            // Читаем как строку, игнорируя Content-Type: text/html
            val responseText = response.body<String>()

            // Ручной парсинг
            json.decodeFromString<SmartyResponse>(responseText)
        } catch (e: Exception) {
            logger.error("Ошибка получения дашборда", e)
            SmartyResponse(status = "ERROR", message = e.message)
        }
    }

    private suspend fun updateLauncherHash(): String? {
        return try {
            val bytes = client.get(AppConfig.OFFICIAL_JAR_URL).body<ByteArray>()
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.error("Ошибка скачивания обновления", e)
            null
        }
    }

    private fun saveHash(hash: String) {
        runCatching { cachedHashFile.writeText(hash) }
    }
}
