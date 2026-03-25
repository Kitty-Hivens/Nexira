package hivens.core.api

import hivens.config.AppConfig
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.SessionData
import hivens.core.util.HashUtils
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Repository for player-specific server actions.
 *
 * Handles operations that affect the player's state on the server,
 * such as resetting spawn position.
 */
class PlayerRepository(
    private val clientProvider: HttpClientProvider,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger("PlayerRepository")
    private val client get() = clientProvider.current

    /**
     * Resets the player's spawn point on the specified server.
     *
     * Only supported on 1.12.2 servers.
     *
     * @param session Current player session (must have valid uid).
     * @param serverId The server's name/id as defined by the API.
     * @return true if the server responded with OK, false otherwise.
     */
    suspend fun resetSpawn(session: SessionData, serverId: String): Boolean {
        val jsonPayload = """{"login":"${session.playerName}","server":"$serverId"}"""

        val timestamp = System.currentTimeMillis() / 1000L / 10L
        val signString = "$timestamp|${session.uid}|${session.playerName}|$serverId"
        val checkHash = HashUtils.md5(signString)

        return try {
            val response = client.post(AppConfig.AUTH_URL) {
                setBody(FormDataContent(Parameters.build {
                    append("action", "spawn")
                    append("json", jsonPayload)
                    append("check", checkHash)
                }))
            }
            val body = response.body<String>().trim()
            logger.info("resetSpawn response for $serverId: '$body'")
            if (body.startsWith("{")) {
                runCatching {
                    json.decodeFromString<SmartyResponse>(body).status == "OK"
                }.getOrDefault(body.contains("OK"))
            } else {
                body.contains("OK")
            }
        } catch (e: Exception) {
            logger.error("Error resetting spawn for ${session.playerName} on $serverId", e)
            false
        }
    }
}
