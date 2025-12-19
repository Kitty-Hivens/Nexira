package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import java.util.concurrent.CompletableFuture

interface IServerListService {
    /**
     * Асинхронно получает список профилей серверов.
     */
    fun fetchProfiles(): CompletableFuture<List<ServerProfile>>
}
