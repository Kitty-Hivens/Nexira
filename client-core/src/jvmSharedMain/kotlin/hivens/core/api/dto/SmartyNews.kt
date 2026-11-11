package hivens.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SmartyNews(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("image") val image: String = "",
    @SerialName("date") val date: Long = 0L,
    @SerialName("views") val views: Int = 0
)
