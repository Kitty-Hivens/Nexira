package hivens.launcher

import hivens.core.data.SessionData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class CredentialsManager(
    workDir: Path,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    // DTO to store only the fields you need (safer than dumping the entire SessionData)
    @Serializable
    private data class SavedCredentials(
        val username: String,
        val accessToken: String,
        val uuid: String,
        val uid: String? = null,
        // It’s bad to store the password in clear text, but for compatibility with SessionData we save it. TODO
        // Ideally, there should be encryption or use of OS Keyring.
        // For simplicity, we encode it in Base64 so that it is not plain-text (protection “from honest people”).
        val savedPasswordBase64: String? = null
    )

    fun save(session: SessionData) {
        // We save only if the user requested or the session is valid
        if (session.accessToken.isBlank()) return

        try {
            val passwordEncoded = session.cachedPassword?.let {
                Base64.getEncoder().encodeToString(it.toByteArray())
            }

            val data = SavedCredentials(
                username = session.playerName,
                accessToken = session.accessToken,
                uuid = session.uuid,
                uid = session.uid,
                savedPasswordBase64 = passwordEncoded
            )

            if (credentialsFile.parent != null) Files.createDirectories(credentialsFile.parent)

            val text = json.encodeToString(data)
            Files.writeString(credentialsFile, text)

        } catch (e: IOException) {
            log.error("Could not save login information", e)
        }
    }

    fun load(): SessionData? {
        if (!Files.exists(credentialsFile)) return null

        return try {
            val text = Files.readString(credentialsFile)
            val data = json.decodeFromString<SavedCredentials>(text)

            val passwordDecoded = data.savedPasswordBase64?.let {
                String(Base64.getDecoder().decode(it))
            }

            // Restoring SessionData.
            // The remaining fields (manifest, balance) will be updated when the profile is updated.
            SessionData(
                playerName = data.username,
                accessToken = data.accessToken,
                uuid = data.uuid,
                uid = data.uid ?: "",
                cachedPassword = passwordDecoded,
                status = null // Status unknown until rechecked
            )
        } catch (e: Exception) {
            log.error("Error reading credentials.json file", e)
            null
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Failed to delete credentials file", e)
        }
    }
}
