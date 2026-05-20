package hivens.core.api

import hivens.core.api.dto.SmartyResponse
import hivens.core.api.interfaces.IServerProtocol
import org.slf4j.LoggerFactory

/**
 * Adapter from [IServerProtocol.loader] to the [SmartyResponse] DTO that
 * existing consumers (`HomeScreen`, `DashboardController`) expect.
 * Separate from direct [IServerProtocol] injection because:
 *   - consumers are typed against `SmartyResponse`; reshape is out of
 *     scope
 *   - when Mirror's protocol arrives, the shape conversion lives here
 *     rather than leaking into the UI
 */
class ServerRepository(
    private val protocol: IServerProtocol,
) {
    private val logger = LoggerFactory.getLogger("ServerRepository")

    /**
     * Fetches dashboard data. Returns either a successful response or a
     * final-failure response after [IServerProtocol]'s internal `UPDATE`
     * retries are exhausted.
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
