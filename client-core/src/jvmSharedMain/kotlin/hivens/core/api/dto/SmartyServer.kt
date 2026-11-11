package hivens.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SmartyServer(
    @SerialName("name") val id: String,
    @SerialName("address") val ip: String,
    @SerialName("port") val port: Int = 25565,
    @SerialName("version") val version: String? = null,
    @SerialName("optionalMods") val optionalMods: JsonElement? = null,
    @SerialName("extraCheckSum") val extraCheckSum: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("online") val online: Int = 0,
    @SerialName("max") val maxPlayers: Int = 100,
    @SerialName("assetDir") private val _assetDir: String? = null
) {
    val assetDir: String
        get() = _assetDir ?: id
}
