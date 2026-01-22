package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.DashboardData
import java.util.concurrent.CompletableFuture

interface IServerListService {
    /**
     * Asynchronously retrieves a list of server profiles.
     */
    fun fetchProfiles(): CompletableFuture<List<ServerProfile>>

    /**
     * Receives complete data for the dashboard: servers + news.
     */
    fun fetchDashboardData(): CompletableFuture<DashboardData>
}
