package hivens.launcher

import hivens.config.Network
import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CompletableFuture

class ServerListService(private val repository: ServerRepository) : IServerListService {

    private var cachedData: DashboardData? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd MMM yyyy", Locale.of("ru"))
        .withZone(ZoneId.systemDefault())

    override fun fetchDashboardData(): CompletableFuture<DashboardData> {
        if (cachedData != null) {
            return CompletableFuture.completedFuture(cachedData)
        }

        return serviceScope.future {
            try {
                val response = repository.fetchDashboard()

                // Data mapping
                val servers = response.servers.map { getProfile(it) }
                val news = response.news.map { newsDto ->
                    val imageName = if (newsDto.image.endsWith(".jpg")) newsDto.image else "${newsDto.image}.jpg"
                    val imageUrl = "${Network.BASE_URL}/images/news/mini/$imageName"

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
                    cachedData = data
                }
                data
            } catch (e: Exception) {
                e.printStackTrace()
                DashboardData(emptyList(), emptyList())
            }
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
