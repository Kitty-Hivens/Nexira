package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.api.model.ServerSource
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CompletableFuture

class SmartyCraftServerListService(
    private val repository: ServerRepository,
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
    private val cache: ServerListCacheStore = ServerListCacheStore.NoOp,
) : IServerListService {

    private val logger = LoggerFactory.getLogger(SmartyCraftServerListService::class.java)
    private val lock = Any()
    @Volatile
    private var cachedData: DashboardData? = null
    /**
     * Single in-flight fetch. If a load is already running when a second
     * caller arrives (auto-sync + dashboard composition + tray-launch
     * can overlap on cold start), they share the same future rather than
     * each firing their own request and racing to populate [cachedData].
     */
    @Volatile
    private var inFlight: CompletableFuture<DashboardData>? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd MMM yyyy", Locale.of("ru"))
        .withZone(ZoneId.systemDefault())

    override fun refresh(): CompletableFuture<DashboardData> {
        synchronized(lock) { cachedData = null }
        return fetchDashboardData()
    }

    override fun fetchDashboardData(): CompletableFuture<DashboardData> {
        cachedData?.let { return CompletableFuture.completedFuture(it) }

        synchronized(lock) {
            // Double-checked: another caller may have populated either field
            // between the unlocked fast-path read and the lock acquire.
            cachedData?.let { return CompletableFuture.completedFuture(it) }
            inFlight?.let { return it }

            val future = serviceScope.future {
                try {
                    val response = repository.fetchDashboard()

                    val servers = response.servers.map { getProfile(it) }
                    val news = response.news.map { newsDto ->
                        val imageName = if (newsDto.image.endsWith(".jpg")) newsDto.image else "${newsDto.image}.jpg"
                        val imageUrl = "${protocolConfig.baseUrl}/images/news/mini/$imageName"

                        NewsItem(
                            id = newsDto.id,
                            title = newsDto.name,
                            description = "Views: ${newsDto.views}",
                            date = formatTimestamp(newsDto.date),
                            imageUrl = imageUrl
                        )
                    }

                    val data = DashboardData(servers, news)
                    if (servers.isNotEmpty()) {
                        synchronized(lock) { cachedData = data }
                        // Disk cache feeds [TrayManager] at the next launch
                        // before the network round-trip; only persist on
                        // success so a transient outage cannot overwrite
                        // the last-known-good list with an empty one.
                        cache.save(servers)
                    }
                    data
                } catch (e: Exception) {
                    logger.error("fetchDashboardData failed -- returning empty dashboard", e)
                    DashboardData(emptyList(), emptyList())
                }
            }
            inFlight = future
            future.whenComplete { _, _ -> synchronized(lock) { inFlight = null } }
            return future
        }
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

    private fun formatTimestamp(ts: Long): String {
        return try {
            dateFormatter.format(Instant.ofEpochSecond(ts))
        } catch (_: Exception) {
            "Unknown Date"
        }
    }
}
