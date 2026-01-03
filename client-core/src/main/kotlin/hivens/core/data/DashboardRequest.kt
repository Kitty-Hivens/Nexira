package hivens.core.data

import kotlinx.serialization.Serializable

@Serializable
data class DashboardRequest(
        val version: String,
        val cheksum: String, // Бейте палками админа проекта, не разработчиков лаунчера!
        val format: String = "jar",
        val testModeKey: String = "false",
        val debug: String = "false"
)
