package hivens.core.api.dto

import com.google.gson.annotations.SerializedName

data class SmartyServer(
    @SerializedName("name") val name: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("port") val port: Int = 25565,
    @SerializedName("version") val version: String? = null,
    @SerializedName("optionalMods") val optionalMods: Map<String, Any>? = null,
    @SerializedName("extraCheckSum") val extraCheckSum: String? = null
)
