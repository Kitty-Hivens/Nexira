package dev.hivens.nexira.host

import android.view.Surface

/**
 * The way into libglfw.so in the process the game runs in: the surface the game draws into and the input it reads.
 * Every call is safe from any thread and before the game has initialised GLFW.
 */
internal object GlfwBridge {
    init {
        System.loadLibrary("nexirahost")
    }

    /** Opens a library by its path so that the game's later open of it, by name or by path, finds it loaded. */
    external fun preload(path: String): Boolean

    /** Finds the host entry points in the GLFW library at [path]. False when it is not there or lacks one. */
    external fun load(path: String): Boolean

    external fun setDisplay(widthPx: Int, heightPx: Int, refreshHz: Float, densityDpi: Float)

    /** Null says the surface is gone. */
    external fun attachSurface(surface: Surface?)

    external fun postKey(androidKeyCode: Int, action: Int, metaState: Int)
    external fun postChar(codepoint: Int)
    external fun postCursor(x: Double, y: Double)
    external fun postMouseButton(button: Int, action: Int, metaState: Int)
    external fun postScroll(dx: Double, dy: Double)
    external fun postRelativeMotion(dx: Double, dy: Double)
    external fun postFocus(focused: Boolean)
    external fun postCloseRequest()
}
