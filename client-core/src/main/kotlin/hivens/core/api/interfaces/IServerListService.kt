package hivens.core.api.interfaces

import hivens.core.data.DashboardData
import java.util.concurrent.CompletableFuture

interface IServerListService {
    /**
     * Receives complete data for the dashboard: servers + news.
     */
    fun fetchDashboardData(): CompletableFuture<DashboardData>
}
