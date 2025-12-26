package hivens.launcher

import hivens.config.ServiceEndpoints
import hivens.core.api.SmartyNetworkService
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.data.DashboardData
import hivens.core.data.NewsItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

class ServerListService(private val networkService: SmartyNetworkService) : IServerListService {

    private var cachedData: DashboardData? = null

    override fun fetchDashboardData(): CompletableFuture<DashboardData> {
        // Если данные есть в кэше, возвращаем их сразу
        if (cachedData != null) {
            return CompletableFuture.completedFuture(cachedData)
        }

        return CompletableFuture.supplyAsync {
            try {
                val response = networkService.getDashboardResponse()
                val servers = response.servers.map { getProfile(it) }
                val news = response.news.map { newsDto ->
                    // Формируем ссылку на картинку
                    val imageName = if (newsDto.image.endsWith(".jpg")) newsDto.image else "${newsDto.image}.jpg"
                    val imageUrl = "${ServiceEndpoints.BASE_URL}/images/news/mini/$imageName"

                    NewsItem(
                        id = newsDto.id,
                        title = newsDto.name,
                        description = "Просмотров: ${newsDto.views}",
                        date = formatTimestamp(newsDto.date),
                        imageUrl = imageUrl
                    )
                }

                val data = DashboardData(servers, news)
                this.cachedData = data
                data
            } catch (e: Exception) {
                e.printStackTrace()
                DashboardData(emptyList(), emptyList())
            }
        }
    }

    override fun fetchProfiles(): CompletableFuture<List<ServerProfile>> {
        return fetchDashboardData().thenApply { it.servers }
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

    private fun formatTimestamp(ts: Long): String {
        return try {
            val date = Date(ts * 1000L)
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.of("ru"))
            sdf.format(date)
        } catch (e: Exception) {
            "Unknown Date"
        }
    }
}
