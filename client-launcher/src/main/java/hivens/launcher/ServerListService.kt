package hivens.launcher

import hivens.core.api.interfaces.IServerListService
import hivens.core.api.SmartyNetworkService
import hivens.core.api.dto.SmartyServer
import hivens.core.api.model.ServerProfile
import java.util.concurrent.CompletableFuture

class ServerListService(private val networkService: SmartyNetworkService) : IServerListService {

    private var cachedProfiles: List<ServerProfile>? = null

    override fun fetchProfiles(): CompletableFuture<List<ServerProfile>> {
        if (!cachedProfiles.isNullOrEmpty()) {
            return CompletableFuture.completedFuture(cachedProfiles)
        }

        return CompletableFuture.supplyAsync {
            val profiles = ArrayList<ServerProfile>()
            try {
                val rawServers = networkService.getServers()
                for (srv in rawServers) {
                    profiles.add(getProfile(srv))
                }
                this.cachedProfiles = profiles
            } catch (e: Exception) {
                System.err.println("Error fetching profiles: " + e.message)
            }
            profiles
        }
    }

    private fun getProfile(srv: SmartyServer): ServerProfile {
        return ServerProfile().apply {
            name = srv.name ?: "Unknown"
            title = srv.name
            version = srv.version ?: "1.7.10"
            ip = srv.address ?: "localhost"
            port = srv.port
            assetDir = srv.name ?: "Unknown"
            extraCheckSum = srv.extraCheckSum
            optionalModsData = srv.optionalMods
        }
    }
}
