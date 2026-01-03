package hivens.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SmartyResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("servers") val servers: List<SmartyServer> = emptyList(),
    @SerialName("news") val news: List<SmartyNews> = emptyList(),
    @SerialName("message") val message: String? = null
)
