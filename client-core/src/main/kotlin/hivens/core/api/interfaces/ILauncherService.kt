package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import hivens.core.launch.SpawnResult
import java.nio.file.Path

interface ILauncherService {
    /**
     * Assembles and spawns the Minecraft launch process; returns a
     * [SpawnResult] -- [SpawnResult.Started] with the process handle, or
     * [SpawnResult.Failed] carrying the semantic launch error.
     */
    @Deprecated(
        "Deprecated since 2.4.0; removed in 2.5.0 at the latest. The SmartyCraft server list is being retired: a pack is the unit of content, and the raw-server path duplicates install, sync and launch with an older, weaker set of guarantees (see #318). New work belongs on launchPackClient.",
        level = DeprecationLevel.WARNING,
    )
    suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
    ): SpawnResult

    /** Same as [launchClient], plus streams stdout / stderr through [onLog]. */
    @Deprecated(
        "Deprecated since 2.4.0; removed in 2.5.0 at the latest. The SmartyCraft server list is being retired: a pack is the unit of content, and the raw-server path duplicates install, sync and launch with an older, weaker set of guarantees (see #318). New work belongs on launchPackClient.",
        level = DeprecationLevel.WARNING,
    )
    suspend fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        adaptiveEnabled: Boolean = false,
        onLog: (String, LauncherLogType) -> Unit,
    ): SpawnResult

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
    suspend fun launchPackClient(
        sessionData: SessionData,
        manifest: CachedManifestSnapshot,
        runtime: InstanceRuntime,
        clientRootPath: Path,
        javaPathOverride: Path?,
        allocatedMemoryMB: Int,
        adaptiveEnabled: Boolean = false,
        // Point authlib's auth/account/session hosts at the SmartyCraft host.
        // Only for a session that IS an SC one -- the redirect decides where the
        // token in hand gets presented, so it defaults to off: a caller that
        // forgets it sends nothing anywhere unexpected, and the cost of the
        // wrong default in that direction is a join that fails loudly rather
        // than a token handed to the wrong host.
        redirectAuthHost: Boolean = false,
        // Attach the authlib-redirect agent for an SC-bound join (default
        // mechanism). No effect on non-SC packs.
        useNetworkAgent: Boolean = true,
        // Swap SC's patched authlib jar onto the classpath instead (opt-in
        // fallback to the agent). No effect on non-SC packs.
        useSmartycraftAuthLib: Boolean = false,
        // This launch will be handed a session token, so what runs beside the
        // game is the launcher's business. Carries the caller's server-binding
        // answer and decides three things at once, because they are one
        // question: the environment loses its loader hooks, the user's own JVM
        // arguments are held to tuning rather than ways to load code, and the
        // natives are re-derived instead of trusted. Defaults to on for the
        // same reason redirectAuthHost defaults to off -- a caller that forgets
        // it errs toward the launch that carries less, not more.
        boundLaunch: Boolean = true,
        // Asked once more with the runtime provisioned and the command built,
        // immediately before the process starts. The check that decided this
        // launch's session had to run before the sign-in it authorises, and the
        // provisioning that follows can take minutes, so the two moments are far
        // apart and the gap is where a file gets added by hand. Returning false
        // stops the launch. Null means nothing to re-assert.
        seal: (suspend () -> Boolean)? = null,
        displayName: String,
        onLog: (String, LauncherLogType) -> Unit,
    ): SpawnResult
}
