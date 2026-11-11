package dev.hivens.nexira.host

/** What the VM tells the process that created it. Called from native threads. */
interface JvmListener {
    /** One line of the game's output, as the bytes it wrote: the VM does not promise valid UTF-8. */
    fun onLine(line: ByteArray)

    /** The game asked to end the process with [code]. The process ends right after this returns. */
    fun onExit(code: Int)
}

/**
 * The game's JVM, started inside this process through the native shim.
 *
 * One JVM per process and it cannot be torn down and made again, so callers run this
 * in a process of its own and let the process go when the game does.
 */
object JvmHost {
    init {
        System.loadLibrary("nexirahost")
    }

    /**
     * Runs [mainClass] in a new JVM from [javaHome] and returns once it, and every
     * thread it started, has finished. Zero, or a negative code naming the step that
     * failed before the game ran.
     */
    external fun run(
        javaHome: String,
        options: Array<String>,
        mainClass: String,
        args: Array<String>,
        listener: JvmListener,
    ): Int

    /** Moves this process into [path]. A relative path the game opens natively goes by this, not by `user.dir`. */
    external fun enterDirectory(path: String): Boolean

    /** Asks the running game to exit as a termination signal would, so its shutdown hooks run. */
    external fun requestExit()
}
