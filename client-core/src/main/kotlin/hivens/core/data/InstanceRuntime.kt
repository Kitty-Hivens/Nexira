package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Per-instance runtime preferences: which JVM to use, how much heap
 * to give it, JVM flags, window geometry, optional auto-connect on
 * launch. Distinct from the existing server-centric `InstanceProfile`
 * because the pack-centric model handles optional content separately
 * via [PackInstance.optionalContent] (lists, not maps; covers mods +
 * shaders + resource packs uniformly).
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
    val javaPath: String? = null,
    val memoryMb: Int = 4096,
    val jvmArgs: String? = null,
    val windowWidth: Int = 925,
    val windowHeight: Int = 530,
    val fullScreen: Boolean = false,
    /**
     * Server identity (in whatever shape the pack's origin uses --
     * SC server id, mirror server id, or `host:port` for a manual
     * entry) to connect to immediately after launch. Null = stay on
     * the in-game multiplayer screen for the user to pick.
     */
    val autoConnectServerId: String? = null,
)
