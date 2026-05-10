package hivens.launcher.platform

/**
 * Coarse OS detection. Read from `os.name` once at class-load and cached
 * — every consumer reads the same JVM property and there's no live OS
 * change to react to.
 *
 * `Darwin` is folded into [isMacOS] because some JVMs (Eclipse OpenJ9 in
 * particular) report `Darwin` instead of the more common `Mac OS X`.
 *
 * Lives in `client-launcher/.../platform/` next to [PlatformPaths] and
 * [SingleInstance] — the platform package owns "what is this machine"
 * concerns; consumers in `client-launcher` and `client-ui` import from
 * here. (No equivalent lives in `client-core` because cross-module the
 * `os.name` system property is the same — there's no abstraction worth
 * extracting.)
 */
object OS {
    private val osName = System.getProperty("os.name").lowercase()

    val isWindows: Boolean = osName.contains("windows")
    val isMacOS: Boolean   = osName.contains("mac") || osName.contains("darwin")
    val isLinux: Boolean   = osName.contains("linux") || osName.contains("unix")

    fun getName(): String = when {
        isWindows -> "Windows"
        isMacOS   -> "macOS"
        isLinux   -> "Linux"
        else      -> "Unknown"
    }
}
