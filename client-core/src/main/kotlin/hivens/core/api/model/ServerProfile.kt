package hivens.core.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ServerProfile(
    var name: String = "",
    var title: String? = null,
    var version: String = "",
    var ip: String = "",
    var port: Int = 0,
    var assetDir: String = "",
    var extraCheckSum: String? = null,
    var optionalModsData: Map<String, JsonElement>? = null
) {
    override fun toString(): String {
        return title ?: name
    }
}
