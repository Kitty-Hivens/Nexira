package dev.hivens.nexira.host

import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.view.Surface

/**
 * What the launcher's process knows about the game that is running: the connection to the process it runs in,
 * and the screen showing it.
 *
 * The surface and the display are kept and replayed when the connection comes up, because the screen is up
 * before the game's process has started listening, and when the screen goes the game is told.
 */
internal object GameSession {
    private val lock = Any()
    private var service: Messenger? = null
    private var surface: Surface? = null
    private var display: Bundle? = null
    private var screen: Activity? = null

    /** The screen showing the game, so it can be closed when the game ends. */
    fun screenUp(activity: Activity) = synchronized(lock) { screen = activity }

    fun screenDown(activity: Activity) = synchronized(lock) {
        if (screen === activity) {
            screen = null
            surface = null
        }
    }

    fun connected(messenger: Messenger) {
        val (pendingSurface, pendingDisplay) = synchronized(lock) {
            service = messenger
            surface to display
        }
        pendingDisplay?.let { send(Message.obtain(null, GameIpc.MSG_DISPLAY).apply { data = it }) }
        pendingSurface?.let { sendSurface(it) }
    }

    /** The game is over: the screen has nothing left to show. */
    fun ended() {
        val activity = synchronized(lock) {
            service = null
            surface = null
            screen.also { screen = null }
        }
        activity?.runOnUiThread { activity.finish() }
    }

    fun surfaceChanged(newSurface: Surface?) {
        synchronized(lock) { surface = newSurface }
        sendSurface(newSurface)
    }

    fun displayChanged(widthPx: Int, heightPx: Int, refreshHz: Float, densityDpi: Float) {
        val bundle = Bundle().apply {
            putInt(GameIpc.KEY_WIDTH, widthPx)
            putInt(GameIpc.KEY_HEIGHT, heightPx)
            putFloat(GameIpc.KEY_REFRESH, refreshHz)
            putFloat(GameIpc.KEY_DENSITY, densityDpi)
        }
        synchronized(lock) { display = bundle }
        send(Message.obtain(null, GameIpc.MSG_DISPLAY).apply { data = bundle })
    }

    fun input(kind: Int, a: Int = 0, b: Int = 0, meta: Int = 0, x: Double = 0.0, y: Double = 0.0) {
        send(Message.obtain(null, GameIpc.MSG_INPUT, kind, 0).apply {
            data = Bundle().apply {
                putInt(GameIpc.KEY_A, a)
                putInt(GameIpc.KEY_B, b)
                putInt(GameIpc.KEY_META, meta)
                putDouble(GameIpc.KEY_X, x)
                putDouble(GameIpc.KEY_Y, y)
            }
        })
    }

    private fun sendSurface(surface: Surface?) {
        send(Message.obtain(null, GameIpc.MSG_SURFACE).apply {
            data = Bundle().apply { if (surface != null) putParcelable(GameIpc.KEY_SURFACE, surface) }
        })
    }

    private fun send(message: Message) {
        val target = synchronized(lock) { service }
        if (target == null) {
            message.recycle()
            return
        }
        val _ = runCatching { target.send(message) }
    }
}
