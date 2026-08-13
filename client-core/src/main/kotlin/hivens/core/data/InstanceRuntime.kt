package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Per-instance runtime preferences: which JVM to use, how much heap
 * to give it, JVM flags, window geometry, optional auto-connect on
 * launch. Distinct from the existing server-centric `InstanceProfile`
 * because the pack-centric model handles optional content separately
 * via [PackInstance.optionalContent] (lists, not maps; covers mods +
 * shaders + resource packs uniformly). What the two do have in common
 * is [RuntimePrefs], which is what the launch path asks for.
 *
 * All fields default to sensible values for a fresh instance so
 * `InstanceRuntime()` is a valid starting state.
 */
@Serializable
data class InstanceRuntime(
    /**
     * Absolute path to a `java` / `java.exe` executable. Null means
     * "let JavaManagerService pick or download the version the pack
     * declares it needs". User-overridable for advanced setups (a
     * custom JDK with specific GC flags, a third-party Java with
     * patched modules, etc).
     */
    override val javaPath: String? = null,
    override val memoryMb: Int = RuntimePrefs.PACK_MEMORY_MB,
    /**
     * Pin this instance to its explicit [memoryMb] instead of the global adaptive
     * sizer. Default false so [SettingsData.adaptiveMemoryEnabled] governs every
     * instance; a pack RAM editor would set it true on an explicit pick. A fresh
     * field (not a flipped opt-in) so instances persisted before it default to
     * adaptive, not to a stale opt-out.
     */
    override val fixedMemory: Boolean = false,
    override val jvmArgs: String? = null,
    override val windowWidth: Int = RuntimePrefs.WINDOW_WIDTH,
    override val windowHeight: Int = RuntimePrefs.WINDOW_HEIGHT,
    override val fullScreen: Boolean = false,
    /**
     * Emit an explicit game-window size (`--width`/`--height`) at launch from
     * [windowWidth]/[windowHeight]. Default false so an instance keeps the
     * client's own remembered size; the pack window's resolution editor flips
     * it true on an explicit pick. A fresh opt-in field, mirroring [fixedMemory]
     * -- instances persisted before it stay on the client default rather than a
     * stale 925x530.
     */
    val windowSizeOverride: Boolean = false,
) : RuntimePrefs
