package hivens.core.data

import hivens.core.api.model.ServerProfile

data class DashboardData(
    val servers: List<ServerProfile> = emptyList(),
    val news: List<NewsItem> = emptyList()
)
