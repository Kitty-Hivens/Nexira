package hivens.core.api.dto

import com.google.gson.annotations.SerializedName

data class SmartyNews(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("image") val image: String = "",
    @SerializedName("date") val date: Long = 0L,
    @SerializedName("views") val views: Int = 0
)
