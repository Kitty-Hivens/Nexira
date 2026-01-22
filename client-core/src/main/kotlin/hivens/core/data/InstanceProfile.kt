package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Settings profile for a specific server.
 * Stores player selection and local settings.
 */
@Serializable
data class InstanceProfile( // TODO: Decide what to do with the default settings in the ui.
    var serverId: String = "",
    var memoryMb: Int = 4096,
    var javaPath: String? = null,
    var jvmArgs: String? = null,
    var windowWidth: Int = 925,
    var windowHeight: Int = 530,
    var fullScreen: Boolean = false,
    var autoConnect: Boolean = true,
    var optionalModsState: MutableMap<String, Boolean> = HashMap()
)
