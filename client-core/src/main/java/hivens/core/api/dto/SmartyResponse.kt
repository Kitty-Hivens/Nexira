package hivens.core.api.dto

import com.google.gson.annotations.SerializedName

data class SmartyResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("servers") val servers: List<SmartyServer> = emptyList()
    // TODO: news и time добавить позже
)
