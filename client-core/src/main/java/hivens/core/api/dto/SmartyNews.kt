package hivens.core.api.dto

import com.google.gson.annotations.SerializedName

data class SmartyNews(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,   // Исправлено: было title
    @SerializedName("image") val image: String, // Исправлено: было imagePath
    @SerializedName("date") val date: Long,     // Исправлено: было dateTimestamp
    @SerializedName("views") val views: Int
)
