package dev.hivens.nexira.host

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.view.Surface

/**
 * What the launcher's process knows about the game that is running: the connection to the process it runs in,
 * and the screen showing it.
 *
 * The surface, the display and the focus are kept and replayed when the connection comes up, because the screen is up
 * before the game's process has started listening, and when the screen goes the game is told.
 */
internal object GameSession {
    private val lock = Any()
    private var service: Messenger? = null
    private var surface: Surface? = null
    private var display: Bundle? = null
    private var focused = false
    private var screen: GameActivity? = null
    private var cursorMode = GameIpc.CURSOR_NORMAL

    /** The screen showing the game, so it can be closed when the game ends. */
    fun screenUp(activity: GameActivity) = synchronized(lock) { screen = activity }

    fun screenDown(activity: GameActivity) = synchronized(lock) {
        if (screen === activity) {
            screen = null
            surface = null
            focused = false
        }
    }

    fun connected(messenger: Messenger) {
        val (pendingSurface, pendingDisplay, pendingFocus) = synchronized(lock) {
            service = messenger
            Triple(surface, display, focused)
        }
        pendingDisplay?.let { send(Message.obtain(null, GameIpc.MSG_DISPLAY).apply { data = it }) }
        pendingSurface?.let { sendSurface(it) }
        if (pendingFocus) input(GameIpc.INPUT_FOCUS, a = 1)
    }

    /** The game is over: the screen has nothing left to show. */
    fun ended() {
        val activity = synchronized(lock) {
            service = null
            surface = null
            focused = false
            cursorMode = GameIpc.CURSOR_NORMAL
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

    fun focusChanged(hasFocus: Boolean) {
        synchronized(lock) { focused = hasFocus }
        input(GameIpc.INPUT_FOCUS, a = if (hasFocus) 1 else 0)
    }

    /** What the game last asked of the cursor, for a screen that comes up after it did. */
    fun cursorMode(): Int = synchronized(lock) { cursorMode }

    fun cursorModeChanged(mode: Int) {
        val activity = synchronized(lock) {
            cursorMode = mode
            screen
        }
        activity?.runOnUiThread { activity.cursorModeChanged(mode) }
    }

    fun input(
        kind: Int,
        a: Int = 0,
        b: Int = 0,
        meta: Int = 0,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        source: Int = GameIpc.SOURCE_DEVICE,
    ) {
        send(Message.obtain(null, GameIpc.MSG_INPUT, kind, 0).apply {
            data = Bundle().apply {
                putInt(GameIpc.KEY_A, a)
                putInt(GameIpc.KEY_B, b)
                putInt(GameIpc.KEY_META, meta)
                putDouble(GameIpc.KEY_X, x)
                putDouble(GameIpc.KEY_Y, y)
                putDouble(GameIpc.KEY_Z, z)
                putInt(GameIpc.KEY_SOURCE, source)
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
