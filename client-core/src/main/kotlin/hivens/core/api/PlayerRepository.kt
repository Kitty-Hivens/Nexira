package hivens.core.api

import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.ProtocolStatus
import hivens.core.data.SessionData
import org.slf4j.LoggerFactory

/**
 * Player-specific server actions over the authenticated channel. Today
 * only [resetSpawn] (1.12.2 gameplay action); future home for any
 * player-state operations Nexira adds on the protocol's auth channel.
 */
class PlayerRepository(
    private val protocol: IServerProtocol,
) {
    private val logger = LoggerFactory.getLogger("PlayerRepository")

    /**
     * Resets the player's spawn point on [serverId] (1.12.2 only).
     * Returns true on [ProtocolStatus.OK]; returns false when [session]
     * has a blank `uid` (refuses to send unsigned) or when the network
     * call fails.
     */
    suspend fun resetSpawn(session: SessionData, serverId: String): Boolean {
        if (session.uid.isBlank()) {
            logger.warn("resetSpawn called with blank uid -- refusing to send unsigned request")
            return false
        }
        val response = try {
            protocol.spawn(uid = session.uid, login = session.playerName, server = serverId)
        } catch (e: Exception) {
            logger.error("resetSpawn network error for {}: {}", serverId, e.message)
            return false
        }
        logger.info("resetSpawn response for {}: {}", serverId, response.status)
        return response.parsedStatus == ProtocolStatus.OK
    }
}
