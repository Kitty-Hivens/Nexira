package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.DashboardData
import java.util.concurrent.CompletableFuture

interface IServerListService {
    /**
     * Асинхронно получает список профилей серверов.
     */
    fun fetchProfiles(): CompletableFuture<List<ServerProfile>>

    /**
     * Получает полные данные для дашборда: серверы + новости.
     */
    fun fetchDashboardData(): CompletableFuture<DashboardData>
}
