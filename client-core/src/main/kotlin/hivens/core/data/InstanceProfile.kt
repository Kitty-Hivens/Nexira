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
     * Pin this server to its explicit [memoryMb] instead of the global adaptive
     * sizer. Set true the moment the user picks a RAM value in RamSelector.
     * Default false so [SettingsData.adaptiveMemoryEnabled] governs every
     * instance out of the box -- a fresh field (not a flipped opt-in) so
     * profiles persisted before it default to adaptive, not to a stale opt-out.
     */
    val fixedMemory: Boolean = false,
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
