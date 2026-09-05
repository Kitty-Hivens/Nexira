package hivens.core.api.model

import hivens.core.data.OptionalMod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val name: String = "",
    val title: String? = null,
    val version: String = "",
    val ip: String = "",
    val port: Int = 0,
    val assetDir: String = "",
    val extraCheckSum: String? = null,
    /**
     * The server's optional mods, keyed by the upstream's own mod id. Decoded
     * at the source adapter, so what a consumer reads here is already a mod
     * rather than a JSON object it has to know the shape of. The wire key is
     * unchanged, so a profile cached by an earlier build still loads.
     */
    @SerialName("optionalModsData")
    val optionalMods: Map<String, OptionalMod> = emptyMap(),
    /** NeoForge launch coordinates this server pins; null leaves them all to detection. */
    val neoForgeArgs: NeoForgeArgs? = null,
    /**
     * Module names to leave out of NeoForge's module path (`-DignoreList`).
     * Empty defers to the launcher's own list for the version.
     */
    val ignoredModules: List<String> = emptyList(),
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
