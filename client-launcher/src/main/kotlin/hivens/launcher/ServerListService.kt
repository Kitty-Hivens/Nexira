package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import hivens.launcher.network.ServerProtocolConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CompletableFuture

class ServerListService(
    private val repository: ServerRepository,
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
) : IServerListService {

    private val logger = LoggerFactory.getLogger(ServerListService::class.java)
    private val lock = Any()
    @Volatile
    private var cachedData: DashboardData? = null
    /**
     * Single in-flight fetch — if dashboard load is already running when a
     * second caller arrives (autosync + dashboard composition + tray-launch
     * can overlap on cold start) they share the same future instead of each
     * firing their own request and racing to populate [cachedData] (#189).
     */
    @Volatile
    private var inFlight: CompletableFuture<DashboardData>? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd MMM yyyy", Locale.of("ru"))
        .withZone(ZoneId.systemDefault())

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
                    }
                    data
                } catch (e: Exception) {
                    logger.error("fetchDashboardData failed — returning empty dashboard", e)
                    DashboardData(emptyList(), emptyList())
                }
            }
            inFlight = future
            future.whenComplete { _, _ -> synchronized(lock) { inFlight = null } }
            return future
        }
    }

    private fun getProfile(srv: SmartyServer): ServerProfile {
        return ServerProfile().apply {
            name = srv.id
            title = srv.title ?: srv.id
            version = srv.version ?: "1.7.10"
            ip = srv.ip
            port = srv.port
            assetDir = srv.assetDir
            extraCheckSum = srv.extraCheckSum
            optionalModsData = (srv.optionalMods as? JsonObject) ?: emptyMap()
        }
    }

    private fun formatTimestamp(ts: Long): String {
        return try {
            dateFormatter.format(Instant.ofEpochSecond(ts))
        } catch (_: Exception) {
            "Unknown Date"
        }
    }
}
