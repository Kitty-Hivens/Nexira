package hivens.core.data

import hivens.core.api.model.ServerProfile

data class DashboardData(
    val servers: List<ServerProfile>,
    val news: List<NewsItem>
)
