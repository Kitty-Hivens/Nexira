package hivens.core.data

import com.google.gson.annotations.SerializedName

/**
 * Модель данных (DTO) для сервера/сборки.
 */
data class ServerData(
    @SerializedName("name") val name: String,
    @SerializedName("address") val address: String,
    @SerializedName("port") val port: Int,
    @SerializedName("version") val version: String
)
