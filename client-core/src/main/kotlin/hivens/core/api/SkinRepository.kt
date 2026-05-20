package hivens.core.api

import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.ProtocolStatus
import hivens.core.data.SessionData
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Skin / cape (cloak) upload adapter over [IServerProtocol]. Returns a
 * human-friendly result string (preserved from the pre-Conduit shape so
 * UI consumers don't have to change); will be reshaped to a typed
 * result when Atelier polishes the upload UX.
 */
class SkinRepository(
    private val protocol: IServerProtocol,
) {
    private val logger = LoggerFactory.getLogger("SkinRepository")

    suspend fun uploadSkin(file: File, isCloak: Boolean, session: SessionData): String {
        if (session.uid.isBlank()) {
            logger.warn("upload{} called with blank uid", if (isCloak) "Cloak" else "Skin")
            return "Connection error: missing session uid"
        }

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            logger.error("Failed to read upload file {}", file, e)
            return "Connection error: ${e.message}"
        }

        val response = if (isCloak) {
            protocol.uploadCloak(uid = session.uid, login = session.playerName, png = bytes)
        } else {
            protocol.uploadSkin(uid = session.uid, login = session.playerName, png = bytes)
        }

        return when (response.parsedStatus) {
            ProtocolStatus.OK -> "OK"
            ProtocolStatus.SIZE -> "Error: Invalid size (need 64x32/64x64)"
            ProtocolStatus.TYPE -> "Error: Invalid file format"
            ProtocolStatus.HD -> "Error: HD skins are not available on your account"
            else -> "Server error: ${response.status}"
        }
    }
}
