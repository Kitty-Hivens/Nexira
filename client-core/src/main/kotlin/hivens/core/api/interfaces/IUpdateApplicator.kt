package hivens.core.api.interfaces

import java.nio.file.Path

/**
 * Schedules a downloaded update installer to apply at JVM exit via
 * [Runtime.addShutdownHook]. Wired through Koin in
 * `client-launcher/.../di/Modules.kt`, implementation picked per
 * [hivens.core.platform.OS]. Lives in `client-core` so UI can
 * inject without seeing platform-specific classes.
 */
interface IUpdateApplicator {
    /**
     * Schedules [installerPath] to install right after JVM exit. The
     * implementation backs up the current binary, relaunches the new
     * version, and rolls back on failure where it can.
     *
     * Throws [UnsupportedOperationException] from the no-op fallback on
     * unrecognized platforms; callers treat that as "ask user to install
     * manually".
     */
    fun scheduleUpdate(installerPath: Path)
}
