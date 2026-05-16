package hivens.core.api

import hivens.core.api.dto.SmartyResponse
import hivens.core.api.interfaces.IServerProtocol
import org.slf4j.LoggerFactory

/**
 * Thin adapter from the new [IServerProtocol.loader] call to the legacy
 * [SmartyResponse] DTO that the rest of the launcher already consumes.
 *
 * Was a much fatter class pre-Conduit (HTTP form-building, launcher hash
 * cache, UPDATE retry loop). All of that moved into
 * [hivens.launcher.protocol.SmartycraftV1Protocol] and
 * [hivens.launcher.protocol.LauncherHashCache] in Phase 1.
 *
 * Kept as a separate class (rather than letting consumers inject
 * [IServerProtocol] directly) for two reasons:
 * - Existing call sites (`HomeScreen`, `DashboardController`) are typed
 *   against `ServerRepository.fetchDashboard()` returning [SmartyResponse].
 *   Reshaping to a different return type is out of Phase 1 scope.
 * - When Mirror's protocol arrives, this adapter is where the shape
 *   conversion will live without leaking through to the UI.
 */
class ServerRepository(
    private val protocol: IServerProtocol,
) {
    private val logger = LoggerFactory.getLogger("ServerRepository")

    /**
     * Retrieves dashboard data from the upstream server. Launcher-hash
     * UPDATE recovery is handled inside [IServerProtocol.loader] -- this
     * method returns either a successful response or a final-failure
     * response (after the protocol's own retry attempts exhausted).
     */
    suspend fun fetchDashboard(): SmartyResponse =
        try {
            val response = protocol.loader()
            SmartyResponse(
                status = response.status,
                servers = response.servers,
                news = response.news,
                message = response.message,
            )
        } catch (e: Exception) {
            logger.error("Error receiving dashboard", e)
            SmartyResponse(status = "ERROR", message = e.message)
        }
}
