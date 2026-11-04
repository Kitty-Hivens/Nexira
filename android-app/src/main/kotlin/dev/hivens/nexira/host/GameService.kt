package dev.hivens.nexira.host

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.Os
import android.util.Log
import android.view.Surface
import dev.hivens.nexira.R
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
                GameIpc.MSG_SURFACE -> HostBridge.attachSurface(surfaceOf(msg.data))
                GameIpc.MSG_DISPLAY -> display(msg.data)
                GameIpc.MSG_INPUT -> input(msg.arg1, msg.data)
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(handler)

    /** One JVM per process: a second start is refused rather than attempted. */
    private var started = false

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        // A game that died is not started again behind the player's back
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.game_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val screen = PendingIntent.getActivity(this, 0, Intent(this, GameActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_game_running)
            .setContentTitle(getString(R.string.game_running))
            .setContentIntent(screen)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION, notification)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The launcher left the front on the way here. The game still runs for as long as Android lets it.
            Log.w(TAG, "no foreground for this game", e)
        }
    }

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
            stopSelf()
            return
        }
        spec.environment.forEach { (name, value) -> Os.setenv(name, value, true) }
        spec.workDir?.let { JvmHost.enterDirectory(it) }
        // Before the VM exists, so that what the screen sends right behind the start finds its way in
        spec.preload.forEach { HostBridge.preload(it) }
        spec.hostLibrary?.let { if (!HostBridge.load(it)) Log.e(TAG, "no window for this launch: $it did not load") }

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

    private fun display(data: Bundle) {
        HostBridge.setDisplay(
            data.getInt(GameIpc.KEY_WIDTH), data.getInt(GameIpc.KEY_HEIGHT),
            data.getFloat(GameIpc.KEY_REFRESH), data.getFloat(GameIpc.KEY_DENSITY),
        )
    }

    private fun input(kind: Int, data: Bundle) {
        val a = data.getInt(GameIpc.KEY_A)
        val b = data.getInt(GameIpc.KEY_B)
        val meta = data.getInt(GameIpc.KEY_META)
        val x = data.getDouble(GameIpc.KEY_X)
        val y = data.getDouble(GameIpc.KEY_Y)
        val z = data.getDouble(GameIpc.KEY_Z)
        val source = data.getInt(GameIpc.KEY_SOURCE)
        when (kind) {
            GameIpc.INPUT_KEY -> HostBridge.postKey(a, b, meta)
            GameIpc.INPUT_TEXT -> HostBridge.postText(a)
            GameIpc.INPUT_POINTER_MOVE -> HostBridge.postPointerMove(x, y, source)
            GameIpc.INPUT_POINTER_BUTTON -> HostBridge.postPointerButton(a, b, meta, source)
            GameIpc.INPUT_SCROLL -> HostBridge.postScroll(x, y)
            GameIpc.INPUT_RELATIVE -> HostBridge.postRelativeMotion(x, y, source)
            GameIpc.INPUT_TOUCH -> HostBridge.postTouch(a, b, x, y, z)
            GameIpc.INPUT_FOCUS -> HostBridge.postFocus(a != 0)
            GameIpc.INPUT_CLOSE -> HostBridge.postClose()
        }
    }

    private fun surfaceOf(data: Bundle): Surface? =
        if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(GameIpc.KEY_SURFACE, Surface::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelable(GameIpc.KEY_SURFACE)
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
        const val CHANNEL = "game"
        const val NOTIFICATION = 1
        const val MAIN_THREAD_STACK = 32L shl 20
        const val GRACE_AFTER_EXIT_MS = 500L
        const val REFUSED = -2
        const val UNREADABLE = -3
    }
}
