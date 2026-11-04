package dev.hivens.nexira.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import hivens.core.data.LauncherLogType
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.launcher.component.GameLaunch
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.launch.GameHost
import hivens.launcher.launch.PackPrepBlocked
import hivens.launcher.launch.PreparedRuntime
import hivens.launcher.launch.RuntimeRequest
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Runs the game on a JVM this application ships, in a process of its own.
 *
 * Where the JVM comes from is [javaHome]'s business: it answers with the directory of
 * the runtime for a Java major, fetching it if need be, or null when no build exists for
 * that major. A pack that wants such a major is refused with the reason instead of being
 * started on the wrong one.
 */
class AndroidGameHost(
    private val context: Context,
    private val javaHome: suspend (major: Int, onProgress: (String) -> Unit) -> Path?,
) : GameHost {

    override suspend fun prepare(request: RuntimeRequest, onLog: (String, LauncherLogType) -> Unit): PreparedRuntime {
        val home = javaHome(request.javaMajor) { onLog(it, LauncherLogType.INFO) }
            ?: throw PackPrepBlocked(LaunchError.Internal("android-java-${request.javaMajor}-unavailable"))
        onLog("Java ${request.javaMajor} from $home", LauncherLogType.INFO)
        return AndroidRuntime(context.applicationContext, home)
    }
}

private class AndroidRuntime(
    private val context: Context,
    private val home: Path,
) : PreparedRuntime {

    override val description: String get() = home.toString()

    override fun start(
        launch: GameLaunch,
        workDir: Path,
        boundLaunch: Boolean,
        onLog: (String, LauncherLogType) -> Unit,
        onDrained: () -> Unit,
        afterExit: () -> Unit,
    ): LaunchHandle {
        // A game that carries the host core library draws a window, and the window is a screen of ours. The libraries
        // are wherever the launcher put the natives: on the library path, or where LWJGL is told to look.
        val nativeDirs = launch.libraryDirectories().map(::File)
        fun nativeLibrary(name: String): File? = nativeDirs.map { File(it, name) }.firstOrNull { it.isFile }
        val host = nativeLibrary("libnxhost.so")
        val spec = LaunchSpec(
            javaHome = home.toString(),
            // What this platform needs of the VM comes first, so what the pack passes wins
            // where the two name the same flag.
            options = HOST_OPTIONS + listOf(
                "-Djava.io.tmpdir=${context.cacheDir.absolutePath}",
                "-Duser.home=${context.filesDir.absolutePath}",
                "-Duser.dir=$workDir",
                // Android keeps the zone in a setting and the JVM looks for it in files a phone does not have
                "-Duser.timezone=${TimeZone.getDefault().id}",
            ) + (if (host != null) windowGameOptions() else emptyList()) + launch.embeddedVmOptions(),
            mainClass = launch.mainClass.replace('.', '/'),
            args = launch.gameArgs,
            hostLibrary = host?.absolutePath,
            // The core first: the stand-ins for GLFW and SDL depend on it by name
            preload = listOfNotNull(host?.absolutePath, nativeLibrary("libMobileGL.so")?.absolutePath),
            environment = mapOf(
                "JAVA_HOME" to home.toString(),
                "HOME" to context.filesDir.absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
            ),
        )
        val specFile = File(context.filesDir, "launches/${UUID.randomUUID()}.json").apply {
            parentFile?.mkdirs()
            writeText(spec.toJson())
        }
        Log.i(TAG, "launch spec ${specFile.name}: ${spec.options.size} VM options, main ${spec.mainClass}")

        val output = QueueInputStream()
        ProcessLogHandler().attachStreams(output, EMPTY, onLog, onDrained)
        if (host != null) {
            context.startActivity(Intent(context, GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return ServiceLaunchHandle(context, specFile, output, afterExit).also { it.connect() }
    }

    private fun windowGameOptions(): List<String> = listOf(
        "-Dorg.lwjgl.opengl.libname=libMobileGL.so",
        // The jars carry the hashes of the stock natives and these are built for this platform
        "-Dorg.lwjgl.util.NoHashChecks=true",
        // No jemalloc is built for bionic, and LWJGL reaches for it by default
        "-Dorg.lwjgl.system.allocator=system",
        // Asked of the VM directly because what it reads inside an app's cpuset can be fewer than the phone has
        "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}",
        // 17 and older take the default from a locale an app process does not set
        "-Dfile.encoding=UTF-8",
        // This JDK is headless, and a loader or a mod that opens a dialog should hear so instead of failing to link
        "-Djava.awt.headless=true",
        // The runtime image carries no jspawnhelper, the default way to start a child process
        "-Djdk.lang.Process.launchMechanism=FORK",
        // Loaders that draw their own window before the game does would need a second surface
        "-Dfml.earlyprogresswindow=false",
        "-Dloader.disable_forked_guis=true",
    )

    private companion object {
        const val TAG = "NexiraHost"

        /** No archive of shared classes in this build, no perf-data file in a directory that does not exist, and the VM leaves the signals to ART. */
        val HOST_OPTIONS = listOf("-Xshare:off", "-XX:-UsePerfData", "-Xrs")
        val EMPTY: InputStream = object : InputStream() { override fun read(): Int = -1 }
    }
}

/**
 * The game as the launcher holds it: a connection to the process it runs in.
 *
 * Output arrives as messages and is fed to the log handler through [output]; the end
 * arrives as a message with the code, or as the connection dropping, which is what a
 * process that was killed looks like.
 */
private class ServiceLaunchHandle(
    private val context: Context,
    private val specFile: File,
    private val output: QueueInputStream,
    private val afterExit: () -> Unit,
) : LaunchHandle {

    private val exit = CompletableDeferred<Int>()
    private var service: Messenger? = null

    // Not the main thread: a message carrying a line is handed to a stream a reader
    // drains at its own pace, and a full buffer must not stop the application.
    private val thread = HandlerThread("game-client").also { it.start() }
    private val handler = object : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GameIpc.MSG_LINE -> msg.data.getByteArray(GameIpc.KEY_LINE)?.let { output.write(it) }
                GameIpc.MSG_EXIT -> finish(msg.arg1)
                else -> super.handleMessage(msg)
            }
        }
    }
    private val client = Messenger(handler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val messenger = Messenger(binder).also { service = it }
            val start = Message.obtain(null, GameIpc.MSG_START).apply {
                replyTo = client
                data = Bundle().apply { putString(GameIpc.KEY_SPEC, specFile.absolutePath) }
            }
            messenger.send(start)
            GameSession.connected(messenger)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The process went without saying why, so the code is not known.
            finish(LOST)
        }

        override fun onBindingDied(name: ComponentName) = finish(LOST)
    }

    fun connect() {
        val intent = Intent(context, GameService::class.java)
        // Started as well as bound: only a foreground service keeps the game's process alive once the screen is left.
        // Refused when the launcher is not in front, and then the game still runs for as long as Android lets it.
        try {
            val _ = context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "the game runs without a foreground service", e)
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(LOST)
    }

    private fun finish(code: Int) {
        if (!exit.complete(code)) return
        GameSession.ended()
        output.close()
        val _ = runCatching { context.unbindService(connection) }
        val _ = runCatching { specFile.delete() }
        thread.quitSafely()
    }

    override suspend fun awaitExit(): Int {
        val code = exit.await()
        afterExit()
        return code
    }

    override fun terminate() {
        val _ = runCatching { service?.send(Message.obtain(null, GameIpc.MSG_TERMINATE)) }
    }

    /** The game has no console to type into on a phone. */
    override val stdin: OutputStream = object : OutputStream() {
        override fun write(b: Int) = Unit
    }

    private companion object {
        const val TAG = "NexiraHost"
        const val LOST = -1
    }
}

/**
 * A stream of bytes that arrive in pieces from another thread, ending when it is
 * closed. [ProcessLogHandler] reads streams, and the game's output comes as messages.
 */
private class QueueInputStream : InputStream() {
    private val pieces = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var at = 0

    @Volatile private var closed = false

    fun write(bytes: ByteArray) {
        // The line ended where the VM's output did; the handler splits on the newline.
        pieces.put(bytes + NEWLINE)
    }

    override fun close() {
        closed = true
        pieces.put(END)
    }

    override fun read(): Int {
        while (at >= current.size) {
            val next = pieces.take()
            if (next === END) {
                pieces.put(END) // a second reader must end as well
                return -1
            }
            current = next
            at = 0
        }
        return current[at++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val first = read()
        if (first < 0) return -1
        buffer[offset] = first.toByte()
        var n = 1
        while (n < length && at < current.size) buffer[offset + n++] = current[at++]
        return n
    }

    private companion object {
        val NEWLINE = byteArrayOf('\n'.code.toByte())
        val END = ByteArray(0)
    }
}
