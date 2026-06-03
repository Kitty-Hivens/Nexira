package hivens.core.data

import kotlinx.serialization.Serializable

/** Per-server profile: player selection + local settings. */
@Serializable
data class InstanceProfile(
    val serverId: String = "",
    /**
     * Default heap for new per-server profiles. 6 GB matches modded-MC
     * reality (SmartyCraft packs run 50-70 mods, need 4-6 GB to be
     * smooth); the constructor screen overwrites this on save.
     */
    val memoryMb: Int = 6144,
    /**
     * Auto heap sizing for this server. The moment the user picks an explicit
     * RAM value in RamSelector this flips to false (explicit wins). Default
     * false so profiles persisted before this field existed keep their stored
     * [memoryMb]; only freshly-created profiles opt in (set true at creation).
     */
    val adaptiveMemory: Boolean = false,
    val javaPath: String? = null,
    val jvmArgs: String? = null,
    val windowWidth: Int = 925,
    val windowHeight: Int = 530,
    val fullScreen: Boolean = false,
    val autoConnect: Boolean = true,
    /**
     * Per-mod enabled/disabled bits. MutableMap so
     * `ServerSettingsScreen.saveProfile()` can update in place without a
     * `copy()` per toggle; surrounding `val` fields preserve safe
     * equality / copy semantics for the record as a whole.
     */
    val optionalModsState: MutableMap<String, Boolean> = HashMap(),
)
