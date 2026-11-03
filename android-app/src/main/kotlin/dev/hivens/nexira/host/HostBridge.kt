package dev.hivens.nexira.host

import android.view.Surface

/**
 * The way into the host core (libnxhost.so) in the process the game runs in: the surface the game draws into and the
 * input it reads. Every call is safe from any thread and before the game has started anything.
 */
internal object HostBridge {
    init {
        System.loadLibrary("nexirahost")
    }

    /** Opens a library by its path so that the game's later open of it, by name or by path, finds it loaded. */
    external fun preload(path: String): Boolean

    /** Finds the core's entry points in the library at [path]. False when it is not there or lacks one. */
    external fun load(path: String): Boolean

    external fun setDisplay(widthPx: Int, heightPx: Int, refreshHz: Float, densityDpi: Float)

    /** Null says the surface is gone. */
    external fun attachSurface(surface: Surface?)

    external fun postKey(androidKeyCode: Int, action: Int, metaState: Int)
    external fun postText(codepoint: Int)
    external fun postPointerMove(x: Double, y: Double, source: Int)
    external fun postPointerButton(button: Int, action: Int, metaState: Int, source: Int)
    external fun postScroll(dx: Double, dy: Double)
    external fun postRelativeMotion(dx: Double, dy: Double, source: Int)
    external fun postTouch(fingerId: Int, action: Int, x: Double, y: Double, pressure: Double)
    external fun postFocus(focused: Boolean)
    external fun postClose()
}
