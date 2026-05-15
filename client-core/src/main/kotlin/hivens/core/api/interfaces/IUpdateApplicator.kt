package hivens.core.api.interfaces

import java.nio.file.Path

/**
 * Schedules a downloaded update installer to be applied when the launcher
 * exits. Implementations are platform-specific — they all run their work
 * via a [Runtime.addShutdownHook] so the user-visible `exitProcess(0)`
 * call from `UpdateDialog` triggers the install.
 *
 * Wired through Koin: a factory in `client-launcher/.../di/Modules.kt`
 * picks the right [IUpdateApplicator] implementation by [hivens.launcher.platform.OS].
 *
 * Lives in `client-core` so `UpdateDialog` (in `client-ui`) can inject the
 * interface without depending on the concrete platform classes — keeps
 * the layering one-way (config ← core ← launcher ← ui).
 */
interface IUpdateApplicator {
    /**
     * Schedules [installerPath] to be installed right after the JVM exits.
     * The implementation is responsible for:
     *   - resolving where the current launcher binary lives,
     *   - backing it up before overwrite,
     *   - relaunching the new version,
     *   - rolling back on failure where it can.
     *
     * Throws [UnsupportedOperationException] from the no-op implementation
     * registered on unrecognized platforms; callers should treat that as
     * "ask the user to download and install manually".
     */
    fun scheduleUpdate(installerPath: Path)
}
