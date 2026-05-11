package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Settings profile for a specific server.
 * Stores player selection and local settings.
 */
@Serializable
data class InstanceProfile( // TODO: Decide what to do with the default settings in the ui.
    var serverId: String = "",
    /**
     * Default heap size for new per-server profiles. 6 GB is the saner-
     * for-modded-MC baseline (SmartyCraft packs run 50-70 mods, need
     * 4-6 GB to be smooth). When the user opens the constructor screen
     * and saves, this gets overwritten with their explicit choice.
     */
    var memoryMb: Int = 6144,
    var javaPath: String? = null,
    var jvmArgs: String? = null,
    var windowWidth: Int = 925,
    var windowHeight: Int = 530,
    var fullScreen: Boolean = false,
    var autoConnect: Boolean = true,
    var optionalModsState: MutableMap<String, Boolean> = HashMap()
)
