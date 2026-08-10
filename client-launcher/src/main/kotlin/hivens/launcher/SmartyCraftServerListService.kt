package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.api.model.ServerSource
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.platform.ServerNameValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * The in-memory dedup + single-flight that this service hand-rolled now lives in
 * [dashboardCache] (an in-memory [Cache] keyed by [CACHE_KEY]). The disk side --
 * the SERVERS-ONLY [cache] that seeds the tray synchronously at next launch --
 * stays as-is, since that seed is read before any coroutine. Concurrent callers
 * (auto-sync + dashboard composition + tray launch overlap on cold start) share
 * one fetch via the cache's single-flight; an empty result (transient outage)
 * neither overwrites the last-known-good list nor caches, so it retries.
 */
class SmartyCraftServerListService(
    private val repository: ServerRepository,
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
    private val cache: ServerListCacheStore = ServerListCacheStore.NoOp,
    private val dashboardCache: Cache<DashboardData> = PassthroughCache(),
) : IServerListService {

    private val logger = LoggerFactory.getLogger(SmartyCraftServerListService::class.java)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun refresh(): CompletableFuture<DashboardData> = serviceScope.future {
        dashboardCache.invalidate(CACHE_KEY)
        dashboardCache.get(CACHE_KEY) { loadDashboard() }
    }

    override fun fetchDashboardData(): CompletableFuture<DashboardData> = serviceScope.future {
        dashboardCache.get(CACHE_KEY) { loadDashboard() }
    }

    private suspend fun loadDashboard(): DashboardData =
        try {
            val response = repository.fetchDashboard()
            // assetDir becomes a directory name under clients/ and a cache-file
            // basename, and it arrives from the server. Screen it here, at the
            // one point where the list enters the launcher, so no downstream
            // caller can be handed a name that resolves somewhere else -- the
            // "reset client" button deletes that directory recursively.
            val servers = response.servers
                .filter { srv ->
                    ServerNameValidator.isValid(srv.assetDir).also { ok ->
                        if (!ok) logger.warn("Dropping server '{}': its assetDir is not a usable directory name", srv.id)
                    }
                }
                .map { getProfile(it) }
            val news = response.news.map { newsDto ->
                val imageName = if (newsDto.image.endsWith(".jpg")) newsDto.image else "${newsDto.image}.jpg"
                NewsItem(
                    id = newsDto.id,
                    title = newsDto.name,
                    views = newsDto.views,
                    dateEpochSeconds = newsDto.date,
                    // Both sizes, so a surface picks the one it draws at. The
                    // thumbnail is what this path always used.
                    imageUrl = "${protocolConfig.baseUrl}/images/news/$imageName",
                    thumbnailUrl = "${protocolConfig.baseUrl}/images/news/mini/$imageName",
                )
            }
            // Persist only on success so a transient outage cannot overwrite the
            // last-known-good list the tray seeds from. The in-memory cache's
            // shouldStore guard does the same for the session cache.
            if (servers.isNotEmpty()) cache.save(servers)
            DashboardData(servers, news)
        } catch (e: Exception) {
            logger.error("fetchDashboardData failed -- returning empty dashboard", e)
            DashboardData(emptyList(), emptyList())
        }

    private fun getProfile(srv: SmartyServer): ServerProfile =
        ServerProfile(
            name             = srv.id,
            title            = srv.title ?: srv.id,
            version          = srv.version ?: "1.7.10",
            ip               = srv.ip,
            port             = srv.port,
            assetDir         = srv.assetDir,
            extraCheckSum    = srv.extraCheckSum,
            optionalModsData = (srv.optionalMods as? JsonObject) ?: emptyMap(),
            source           = ServerSource.Smartycraft,
        )

    private companion object {
        // One dashboard per session; a fixed key is enough.
        const val CACHE_KEY = "dashboard"
    }
}
