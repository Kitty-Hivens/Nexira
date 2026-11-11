package hivens.launcher.launch

import hivens.core.data.LauncherLogType
import hivens.core.launch.LaunchHandle
import hivens.launcher.component.GameLaunch
import java.nio.file.Path

/**
 * Where the game runs.
 *
 * What the pack needs and what the game is called with are the same on every
 * platform and are decided before this. What is not the same is the JVM itself: a
 * desktop host runs the game as a child process of a Java it finds or downloads, a
 * host on a phone embeds a JVM it ships. The launch service asks the host for a
 * runtime once it knows which Java major the pack declares, and starts the game
 * through the runtime it got back.
 */
interface GameHost {

    /**
     * The runtime the game will run on, provisioned if this host manages one.
     * Throws [PackPrepBlocked] when the launch must not go ahead with what exists.
     */
    suspend fun prepare(request: RuntimeRequest, onLog: (String, LauncherLogType) -> Unit): PreparedRuntime
}

data class RuntimeRequest(
    val javaMajor: Int,
    /** The instance's own Java, which beats everything else. Null or empty for none. */
    val pinnedJava: String?,
    /** The user's global Java, when set. */
    val overrideJava: Path?,
    /** A launch that will carry a session token. */
    val boundLaunch: Boolean,
)

interface PreparedRuntime {

    /** Names the runtime in the log, for a human. */
    val description: String

    /**
     * Starts [launch] with [workDir] as the game directory. Output lines go to
     * [onLog], [onDrained] runs once both of the game's output streams have ended,
     * and [afterExit] once the game has exited.
     */
    fun start(
        launch: GameLaunch,
        workDir: Path,
        boundLaunch: Boolean,
        onLog: (String, LauncherLogType) -> Unit,
        onDrained: () -> Unit,
        afterExit: () -> Unit,
    ): LaunchHandle
}
