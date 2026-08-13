package hivens.core.data

import kotlinx.serialization.Serializable

/** Per-server profile: player selection + local settings. */
@Serializable
data class InstanceProfile(
    val serverId: String = "",
    /** Starting heap for a new per-server profile; the constructor screen overwrites it on save. */
    override val memoryMb: Int = RuntimePrefs.SERVER_MEMORY_MB,
    /**
     * Pin this server to its explicit [memoryMb] instead of the global adaptive
     * sizer. Set true the moment the user picks a RAM value in RamSelector.
     * Default false so [SettingsData.adaptiveMemoryEnabled] governs every
     * instance out of the box -- a fresh field (not a flipped opt-in) so
     * profiles persisted before it default to adaptive, not to a stale opt-out.
     */
    override val fixedMemory: Boolean = false,
    override val javaPath: String? = null,
    override val jvmArgs: String? = null,
    override val windowWidth: Int = RuntimePrefs.WINDOW_WIDTH,
    override val windowHeight: Int = RuntimePrefs.WINDOW_HEIGHT,
    override val fullScreen: Boolean = false,
    val autoConnect: Boolean = true,
    /**
     * Per-mod enabled/disabled bits. An immutable [Map] so the record keeps
     * value semantics: `copy()` no longer shares a mutable backing map between
     * two profiles (which let a mutation on one silently alias the other).
     * Updated by rebuilding via `copy(optionalModsState = ...)`.
     */
    val optionalModsState: Map<String, Boolean> = emptyMap(),
) : RuntimePrefs
