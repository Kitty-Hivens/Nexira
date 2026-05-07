package hivens.core.api

import hivens.config.Network
import hivens.config.Protocol
import hivens.config.Storage
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.DashboardRequest
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

class ServerRepository(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    dataDir: File? = null
) {
    private val logger = LoggerFactory.getLogger("ServerRepository")
    private val cachedHashFile = dataDir?.let { File(it, Storage.HASH_CACHE_FILE) }
        ?: File(Storage.HASH_CACHE_FILE)
    private var currentHash = Protocol.DEFAULT_LAUNCHER_HASH
    private val client get() = clientProvider.current

    init {
        if (cachedHashFile.exists()) {
            runCatching {
                val cached = cachedHashFile.readText().trim()
                if (cached.isNotBlank()) currentHash = cached
            }
        }
    }

    /**
     * Retrieves dashboard data. If the server requests an update (UPDATE) -
     * downloads the JAR, updates the hash and repeats the request.
     */
    suspend fun fetchDashboard(): SmartyResponse {
        var response = requestDashboard(currentHash)

        if (response.status == "UPDATE") {
            logger.info("UPDATE status. Let's start updating the hash...")
            val newHash = updateLauncherHash()
            if (newHash != null) {
                currentHash = newHash
                saveHash(newHash)
                response = requestDashboard(newHash)
            } else {
                logger.error("Launcher update failed. We return it as is.")
            }
        }
        return response
    }

    private suspend fun requestDashboard(hash: String): SmartyResponse {
        // We generate JSON manually, since the server expects a JSON string inside the "json" form field
        // This is a specificity of the old SmartyCraft PHP backend
        val requestPayload = DashboardRequest(
            version = Protocol.MIMIC_LAUNCHER_VERSION,
            cheksum = hash
        )

        val payload = json.encodeToString(requestPayload)

        return try {
            val response = client.post(Network.AUTH_URL) {
                setBody(FormDataContent(Parameters.build {
                    append("action", "loader")
                    append("json", payload)
                }))
            }
            // Read as a string, ignoring Content-Type: text/html
            val responseText = response.body<String>()

            // Manual parsing
            json.decodeFromString<SmartyResponse>(responseText)
        } catch (e: Exception) {
            logger.error("Error receiving dashboard", e)
            SmartyResponse(status = "ERROR", message = e.message)
        }
    }

    private suspend fun updateLauncherHash(): String? {
        return try {
            val bytes = client.get(Network.OFFICIAL_JAR_URL).body<ByteArray>()
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.error("Error downloading update", e)
            null
        }
    }

    private fun saveHash(hash: String) {
        runCatching { cachedHashFile.writeText(hash) }
    }
}
