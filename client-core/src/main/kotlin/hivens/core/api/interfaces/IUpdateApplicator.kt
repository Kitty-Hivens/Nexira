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

    /**
     * Where the downloaded bytes should be written, given the launcher's own
     * updates directory and the asset's file name.
     *
     * The default answers with the updates directory, which is what a platform
     * whose installer is a separate program wants. An implementation that
     * installs by replacing files answers with a path beside its own install, so
     * [scheduleUpdate] can put the update in place with a rename instead of a
     * copy -- the copy would otherwise run after the process has been told to
     * exit, where nothing can report it and the window is already dead.
     */
    fun stagingPath(fallbackDir: Path, fileName: String): Path = fallbackDir.resolve(fileName)

    /**
     * Staged files left beside the install by a download the user never
     * installed. The updates directory is swept by the update service itself;
     * anything [stagingPath] puts elsewhere has to name itself here or it is
     * never collected.
     */
    fun stagedLeftovers(): List<Path> = emptyList()
}
