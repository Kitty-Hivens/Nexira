package hivens.core.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ServerProfile(
    val name: String = "",
    val title: String? = null,
    val version: String = "",
    val ip: String = "",
    val port: Int = 0,
    val assetDir: String = "",
    val extraCheckSum: String? = null,
    val optionalModsData: Map<String, JsonElement>? = null,
    val neoForgeArgs: Map<String, String>? = null,
    val ignoreModulesList: String? = null,
) {
    override fun toString(): String = title ?: name
}
