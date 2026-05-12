package hivens.core.api

import hivens.config.Network
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class SkinRepository(
    private val clientProvider: HttpClientProvider,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger("SkinRepository")
    private val client get() = clientProvider.current

    suspend fun uploadSkin(file: File, isCloak: Boolean, session: SessionData): String {
        val type = if (isCloak) "cloak" else "skin"
        val action = if (isCloak) "cloakupload" else "skinupload"
        
        // 1. Preparation JSON payload (login)
        val jsonPayload = """{"login":"${session.playerName}"}"""

        // 2. Generation of signatures (MD5)
        // Logic from SmartyNetworkService: MD5( (time/10) + "|" + uid + "|" + login )
        val timestamp = System.currentTimeMillis() / 1000L / 10L
        val signString = "$timestamp|${session.uid}|${session.playerName}"
        val checkHash = HashUtils.md5(signString)

        return try {
            val response = client.post(Network.AUTH_URL) {
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

            // Response processing. The server can return JSON or just text.
            val bodyText = response.body<String>().trim()
            
            // Trying to parse as JSON
            if (bodyText.startsWith("{")) {
                try {
                    val respObj = json.decodeFromString<SmartyResponse>(bodyText)
                    if (respObj.status == "OK") return "OK"
                    return mapErrorStatus(respObj.status)
                } catch (e: Exception) {
                    logger.debug("Skin upload response started with '{{' but failed JSON decode; falling through to text-based check", e)
                }
            }

            if (bodyText.contains("OK")) return "OK"

            return bodyText
            
        } catch (e: Exception) {
            logger.error("Error loading asset", e)
            "Connection error: ${e.message}"
        }
    }

    private fun mapErrorStatus(status: String?): String {
        return when (status) {
            "SIZE" -> "Error: Invalid size (need 64x32/64x64)"
            "TYPE" -> "Error: Invalid file format"
            "HD" -> "Error: HD skins are not available on your account"
            else -> "Server error: $status"
        }
    }
}
