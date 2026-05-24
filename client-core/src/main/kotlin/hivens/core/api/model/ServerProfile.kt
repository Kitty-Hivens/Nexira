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
    /**
     * Default is [ServerSource.Smartycraft] because every persisted
     * ServerProfile from before this field existed originated from
     * the SC dashboard. Cached profiles deserialise into Smartycraft
     * automatically; new profiles produced by the mirror impl set
     * the field explicitly.
     */
    val source: ServerSource = ServerSource.Smartycraft,
) {
    override fun toString(): String = title ?: name
}
