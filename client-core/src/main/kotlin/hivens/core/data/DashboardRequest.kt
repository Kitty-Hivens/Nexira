package hivens.core.data

import kotlinx.serialization.Serializable

@Serializable
data class DashboardRequest( // TODO: NOT USED
        val version: String,
        val cheksum: String, // Typo in external API (admin's fault, not ours!)
        val format: String = "jar",
        val testModeKey: String = "false",
        val debug: String = "false"
)
