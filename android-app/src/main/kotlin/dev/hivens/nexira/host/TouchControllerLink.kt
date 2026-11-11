package dev.hivens.nexira.host

import android.content.Context
import android.os.Process
import android.os.Vibrator
import android.util.Log
import top.fifthlight.touchcontroller.proxy.client.LauncherProxyClient
import top.fifthlight.touchcontroller.proxy.client.MessageTransport
import top.fifthlight.touchcontroller.proxy.client.PlatformCapability
import top.fifthlight.touchcontroller.proxy.client.android.SimpleVibrationHandler
import top.fifthlight.touchcontroller.proxy.client.android.transport.UnixSocketTransport
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher's end of the TouchController mod: a socket the mod connects to from inside the game, the fingers going
 * out through it and the mod's requests for a keyboard or a vibration coming back.
 *
 * The socket is up for every game, because whether a pack carries the mod is only known once the mod speaks. Until it
 * does, [connected] is false and the screen goes on treating a finger as a mouse.
 */
internal class TouchControllerLink private constructor(
    /** The socket's name, which the mod is told through [SOCKET_VARIABLE]. */
    val socketName: String,
    private val client: LauncherProxyClient,
    private val heard: HeardTransport,
) : AutoCloseable {

    val connected: Boolean get() = heard.heard

    /** [index] only ever grows for one game, [x] and [y] run from 0 to 1 across the game's view. */
    fun pointer(index: Int, x: Float, y: Float) = client.addPointer(index, x, y)

    fun liftPointer(index: Int) = client.removePointer(index)

    /** Every finger is gone with no lift of its own to report. */
    fun clearPointers() = client.clearPointer()

    override fun close() = client.close()

    /** Tells whether anything has come in, which is how the mod being there shows. */
    private class HeardTransport(private val inner: MessageTransport) : MessageTransport {
        @Volatile var heard = false
            private set

        override fun send(buffer: ByteBuffer) = inner.send(buffer)

        override fun receive(buffer: ByteBuffer): Boolean = inner.receive(buffer).also { if (it) heard = true }

        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "NexiraTouch"

        /** Where the mod looks for the socket, set in the game's process before the VM starts. */
        const val SOCKET_VARIABLE = "TOUCH_CONTROLLER_PROXY_SOCKET"
        private const val SOCKET_PREFIX = "dev.hivens.nexira.touchcontroller."
        private val socketsOpened = AtomicInteger()

        /**
         * Null when the socket cannot be made, and then the game runs with a finger as a mouse.
         *
         * Every game gets a name of its own. The library closes a socket on a thread of its own, and one still held
         * after its game is gone kept the next game from binding a shared name, which left that game without the mod.
         */
        fun open(context: Context): TouchControllerLink? = try {
            val name = SOCKET_PREFIX + "${Process.myPid()}.${socketsOpened.incrementAndGet()}"
            val heard = HeardTransport(UnixSocketTransport(name))
            val client = LauncherProxyClient(heard, setOf(PlatformCapability.KEYBOARD_SHOW))
            client.vibrationHandler = SimpleVibrationHandler(context.getSystemService(Vibrator::class.java))
            client.keyboardShowHandler = object : LauncherProxyClient.KeyboardShowHandler {
                override fun showKeyboard() = GameSession.softKeyboard(true)
                override fun hideKeyboard() = GameSession.softKeyboard(false)
            }
            client.run()
            TouchControllerLink(name, client, heard)
        } catch (e: Exception) {
            Log.w(TAG, "no socket for the touch controller", e)
            null
        }
    }
}
