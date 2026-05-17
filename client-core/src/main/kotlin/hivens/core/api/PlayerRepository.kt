package hivens.core.api

import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.ProtocolStatus
import hivens.core.data.SessionData
import org.slf4j.LoggerFactory

/**
 * Repository for player-specific server actions.
 *
 * Currently only [resetSpawn] (1.12.2-only gameplay action). Becomes the
 * home for any future player-state operations Aura adds (cosmetics, friend
 * lists, etc.) that hit the protocol's authenticated channel.
 *
 * Pre-Conduit: built the wire request directly. Post-Conduit Phase 1: just
 * adapts call sites to [IServerProtocol].
 */
class PlayerRepository(
    private val protocol: IServerProtocol,
) {
    private val logger = LoggerFactory.getLogger("PlayerRepository")

    /**
     * Resets the player's spawn point on the specified server.
     * Only supported on 1.12.2 servers.
     *
     * @param session Current player session (must have a non-blank `uid`).
     * @param serverId Server name as defined by the loader response.
     * @return true if the server responded with [ProtocolStatus.OK], false otherwise.
     */
    suspend fun resetSpawn(session: SessionData, serverId: String): Boolean {
        if (session.uid.isBlank()) {
            logger.warn("resetSpawn called with blank uid -- refusing to send unsigned request")
            return false
        }
        // Boolean return contract: any network failure folds into `false` so
        // UI call sites never have to special-case IOException. Protocol layer
        // now propagates rather than swallowing (Conduit Phase 1 follow-up).
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
