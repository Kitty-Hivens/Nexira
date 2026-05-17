package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Settings profile for a specific server.
 * Stores player selection and local settings.
 */
@Serializable
data class InstanceProfile( // TODO: Decide what to do with the default settings in the ui.
    val serverId: String = "",
    /**
     * Default heap size for new per-server profiles. 6 GB is the saner-
     * for-modded-MC baseline (SmartyCraft packs run 50-70 mods, need
     * 4-6 GB to be smooth). When the user opens the constructor screen
     * and saves, this gets overwritten with their explicit choice.
     */
    val memoryMb: Int = 6144,
    val javaPath: String? = null,
    val jvmArgs: String? = null,
    val windowWidth: Int = 925,
    val windowHeight: Int = 530,
    val fullScreen: Boolean = false,
    val autoConnect: Boolean = true,
    /**
     * Per-mod enabled/disabled bits. Stays a `MutableMap` because
     * `ServerSettingsScreen.saveProfile()` does in-place update after
     * the surrounding fields are switched to `val`; the rest of the
     * record's immutability is sufficient for safe equality / copy
     * semantics, and switching this to an immutable map would force a
     * second `copy()` on every toggle.
     */
    val optionalModsState: MutableMap<String, Boolean> = HashMap(),
)
