package dev.hivens.nexira.host

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.io.File

/**
 * The game, in a process of its own.
 *
 * Declared in the manifest as `:jvm`, so the JVM it creates lives and dies with this
 * process and the launcher is never in the same address space as a game that might
 * take it down. The launcher binds, sends the spec of a launch, and hears the output
 * and the end of the game back.
 */
class GameService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GameIpc.MSG_START -> start(msg.replyTo, msg.data.getString(GameIpc.KEY_SPEC))
                GameIpc.MSG_TERMINATE -> terminate()
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(handler)

    /** One JVM per process: a second start is refused rather than attempted. */
    private var started = false

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun start(client: Messenger?, specPath: String?) {
        if (client == null || specPath == null) return
        if (started) {
            client.send(Message.obtain(null, GameIpc.MSG_EXIT, REFUSED, 0))
            return
        }
        started = true
        val spec = try {
            LaunchSpec.fromJson(File(specPath).readText())
        } catch (e: Exception) {
            Log.e(TAG, "unreadable launch spec $specPath", e)
            client.send(Message.obtain(null, GameIpc.MSG_EXIT, UNREADABLE, 0))
            return
        }
        val listener = object : JvmListener {
            override fun onLine(line: ByteArray) {
                val m = Message.obtain(null, GameIpc.MSG_LINE)
                m.data = Bundle().apply { putByteArray(GameIpc.KEY_LINE, line) }
                val _ = runCatching { client.send(m) }
            }

            override fun onExit(code: Int) {
                val _ = runCatching { client.send(Message.obtain(null, GameIpc.MSG_EXIT, code, 0)) }
            }
        }
        // A thread with a stack of its own: the JVM's main thread is whichever thread
        // creates it, and the launcher it replaces gives it a larger one than a default.
        Thread(null, {
            val code = JvmHost.run(spec.javaHome, spec.options.toTypedArray(), spec.mainClass, spec.args.toTypedArray(), listener)
            val _ = runCatching { client.send(Message.obtain(null, GameIpc.MSG_EXIT, code, 0)) }
            // The VM cannot be made again in this process, so the process goes with it.
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, GRACE_AFTER_EXIT_MS)
        }, "game-jvm-main", MAIN_THREAD_STACK).start()
    }

    private fun terminate() {
        if (!started) return
        JvmHost.requestExit()
        handler.postDelayed({
            Log.w(TAG, "the game did not exit after the request, ending the process")
            Process.killProcess(Process.myPid())
        }, GameIpc.TERMINATE_GRACE_MS)
    }

    private companion object {
        const val TAG = "NexiraGame"
        const val MAIN_THREAD_STACK = 32L shl 20
        const val GRACE_AFTER_EXIT_MS = 500L
        const val REFUSED = -2
        const val UNREADABLE = -3
    }
}
