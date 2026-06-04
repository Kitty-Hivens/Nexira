package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import java.io.IOException
import java.nio.file.Path

interface ILauncherService {
    /**
     * Assembles and spawns the Minecraft launch process; returns the
     * running [Process] for monitoring.
     */
    @Throws(IOException::class)
    suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
    ): Process

    /** Same as [launchClient], plus streams stdout / stderr through [onLog]. */
    @Throws(IOException::class)
    suspend fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        adaptiveEnabled: Boolean = false,
        onLog: (String, LauncherLogType) -> Unit,
    ): Process

    /**
     * Pack-centric launch path. Spawns the JVM against a Hivens
     * mirror pack instance, using the [runtime] settings (heap, JVM
     * args, java path) tied to the [PackInstance] and the static
     * [manifest] snapshot recorded at install / sync time.
     *
     * The legacy [launchClient] / [launchClientWithLogs] path is SC
     * server-centric and reaches for per-server [InstanceProfile]
     * data via the legacy [hivens.launcher.ProfileManager]; this
     * method is the equivalent for pack-centric instances and bypasses
     * that whole branch.
     *
     * @param sessionData      Player session (player name, uuid,
     *                         accessToken). Pack-centric mirror packs
     *                         do not require a fresh auth call before
     *                         launch; the in-game join is what
     *                         actually exercises auth.
     * @param manifest         Snapshot of mirror-manifest values the
     *                         command builder needs. Persisted on the
     *                         [PackInstance] at install time so a Play
     *                         click never makes a network round trip.
     * @param runtime          Per-instance JVM preferences (heap, args,
     *                         java path).
     * @param clientRootPath   Absolute path to the instance's directory
     *                         (`<dataDir>/instances/<instanceDirName>`).
     * @param javaPathOverride Optional global Java override (the user's
     *                         `settings.javaPath`). When null, the launcher
     *                         provisions the loader-declared Java itself
     *                         from the resolved runtime's `javaMajor`, which
     *                         beats the version heuristic (same MC + different
     *                         loader can need different Java -- Cleanroom-1.12.2
     *                         wants 25, legacy-Forge-1.12.2 wants 8). The
     *                         per-instance [InstanceRuntime.javaPath] still wins
     *                         over both override and managed default.
     * @param allocatedMemoryMB Fallback heap (MB) when the instance's
     *                         own [InstanceRuntime.memoryMb] is 0 or
     *                         below the launcher floor.
     * @param displayName      Human label for log lines.
     * @param onLog            Stdout / stderr line callback.
     */
    @Throws(IOException::class)
    suspend fun launchPackClient(
        sessionData: SessionData,
        manifest: CachedManifestSnapshot,
        runtime: InstanceRuntime,
        clientRootPath: Path,
        javaPathOverride: Path?,
        allocatedMemoryMB: Int,
        adaptiveEnabled: Boolean = false,
        displayName: String,
        onLog: (String, LauncherLogType) -> Unit,
    ): Process
}
