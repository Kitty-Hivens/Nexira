package hivens.launcher.launch

import hivens.core.api.interfaces.IJavaManager
import hivens.core.data.LauncherLogType
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.launcher.component.GameLaunch
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.security.JavaBinary
import hivens.launcher.security.LaunchEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the game as a child process of a Java found on the machine, or downloaded
 * for the major the pack declares.
 */
internal class ProcessGameHost(
    private val javaManager: IJavaManager,
    private val logHandler: ProcessLogHandler,
) : GameHost {

    private val log = LoggerFactory.getLogger(ProcessGameHost::class.java)

    /**
     * Path precedence: the instance's pin, then the caller's override, then the
     * managed Java for the declared major. A managed JDK that would be discarded is
     * not provisioned: when the instance pins its own Java, nothing is downloaded.
     */
    override suspend fun prepare(request: RuntimeRequest, onLog: (String, LauncherLogType) -> Unit): PreparedRuntime {
        val pinned = request.pinnedJava
        val javaExec: String = if (!pinned.isNullOrEmpty()) {
            pinned
        } else {
            val defaultJava = request.overrideJava ?: javaManager.getJavaPathForMajor(request.javaMajor) { msg ->
                onLog(msg, LauncherLogType.INFO)
            }
            existingOrPlain(defaultJava)
        }

        // A launch that will carry a token runs the interpreter it was given, and
        // that interpreter decides everything the command line just decided. A
        // wrapper script in its place makes all of it someone else's choice.
        if (request.boundLaunch && !JavaBinary.isNativeExecutable(Path.of(javaExec))) {
            log.error("Refusing a bound launch: {} is not a native executable", javaExec)
            onLog("The Java runtime at $javaExec is not a program -- refusing to launch", LauncherLogType.ERROR)
            throw PackPrepBlocked(LaunchError.Internal("java-not-executable"))
        }
        return ProcessRuntime(javaExec, logHandler)
    }

    internal companion object {
        /**
         * The resolved Java when it is there, else `java` on the path. The managed-Java
         * step has already been taken by now, against the pack's declared major.
         */
        internal fun existingOrPlain(defaultPath: Path): String =
            if (Files.exists(defaultPath)) defaultPath.toString() else "java"
    }
}

private class ProcessRuntime(
    private val javaExec: String,
    private val logHandler: ProcessLogHandler,
) : PreparedRuntime {

    override val description: String get() = javaExec

    /**
     * Builds, starts, and log-attaches the game process. The launch runs on the
     * caller's IO dispatcher, so the blocking ProcessBuilder.start happens on IO
     * without an extra context switch.
     */
    override fun start(
        launch: GameLaunch,
        workDir: Path,
        boundLaunch: Boolean,
        onLog: (String, LauncherLogType) -> Unit,
        onDrained: () -> Unit,
        afterExit: () -> Unit,
    ): LaunchHandle {
        val command = launch.commandLine(javaExec)
        val pb = ProcessBuilder(command)
        pb.directory(workDir.toFile())
        pb.redirectErrorStream(false)
        // The game inherits this process's environment, which inherited the
        // session's, so a value in a shell profile reaches every launch. Named in
        // the log rather than dropped quietly: a user who set one deliberately is
        // owed the reason their tool stopped attaching.
        LaunchEnvironment.seal(pb.environment(), boundLaunch).forEach {
            onLog("Sealed $it out of the game environment", LauncherLogType.INFO)
        }
        onLog("CMD: ${command.joinToString(" ")}", LauncherLogType.INFO)
        val process = pb.start()
        logHandler.attach(process, onLog, onDrained)
        return ProcessLaunchHandle(process, afterExit)
    }
}

/**
 * Wraps the spawned [Process] so the core SPI hands back a [LaunchHandle]
 * instead of the JVM type. [awaitExit] blocks the calling dispatcher (the
 * launcher's IO launch coroutine), mirroring the prior in-coroutine
 * `process.waitFor()` -- cancelling the launch job does not interrupt it, so
 * the orchestrator sends [terminate] first to let the wait return.
 */
internal class ProcessLaunchHandle(
    private val process: Process,
    /** Runs once the game has exited, for what the launch changed only for its own duration. */
    private val afterExit: () -> Unit = {},
) : LaunchHandle {
    // On IO by its own doing rather than by the caller's promise: the wait is
    // unbounded, and a blocking wait that borrows whatever thread it was called on
    // is one refactor away from parking a dispatcher that had other work.
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        val code = process.waitFor()
        afterExit()
        code
    }

    /**
     * SIGTERM, then SIGKILL if the game did not take the hint.
     *
     * SIGTERM is handled by a JVM shutdown hook, so a healthy game runs it, saves
     * and exits -- which is why the polite signal goes first. A wedged JVM never
     * reaches the hook, and re-sending the signal changes nothing there: it is
     * already pending, and the kernel does not make a delivered signal more
     * insistent by repetition. Only [Process.destroyForcibly] ends that, because
     * nothing in user space gets a say in it.
     *
     * The escalation runs on its own thread. [terminate] is called from
     * `LauncherController.abort`, which is a plain function invoked from a Compose
     * click handler -- blocking there would freeze the window on exactly the
     * process that is refusing to die.
     *
     * Descendants are listed first, before any signal: killing the parent orphans
     * them, an orphan is no longer among its descendants, and on Windows
     * `destroyForcibly` does not reach them at all. They were listed only on the
     * escalation, after the parent was gone, so a game that took the polite signal
     * left whatever it had started running, and a launch through a wrapper script
     * that does not exec left the game itself. What outlives the parent is given
     * the same polite signal and the same grace, then the forced one.
     */
    override fun terminate() {
        val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        val _ = runCatching { process.destroy() }
        Thread {
            val exited = runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!exited) {
                val _ = runCatching { process.destroyForcibly() }
            }
            val left = children.filter { it.isAlive }
            left.forEach { val _ = runCatching { it.destroy() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATE_GRACE_SECONDS)
            left.forEach { child ->
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
                val gone = runCatching { child.onExit().get(remaining, TimeUnit.NANOSECONDS); true }.getOrDefault(false)
                if (!gone) {
                    val _ = runCatching { child.destroyForcibly() }
                }
            }
        }.apply {
            isDaemon = true
            name = "game-terminate-escalation"
        }.start()
    }

    override val stdin: OutputStream get() = process.outputStream

    private companion object {
        /** Long enough for a modded client to run its shutdown hook and save. */
        const val TERMINATE_GRACE_SECONDS = 8L
    }
}
