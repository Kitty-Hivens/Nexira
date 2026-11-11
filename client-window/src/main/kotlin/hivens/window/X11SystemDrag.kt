package hivens.ui.chrome

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Hands a drag on the undecorated window's caption or edge to the X11 window
 * manager through EWMH's `_NET_WM_MOVERESIZE`, so the window manager moves or
 * resizes the window itself, with its own snapping and edge resistance, the way
 * it does for a window with a frame of its own. The X11 counterpart of
 * [Win32Frame], for a floating window manager under X11 or XWayland.
 *
 * The press arrived through AWT's connection to the X server, and the server
 * gave that connection an implicit grab of the pointer for it. The window
 * manager cannot take the pointer while the grab stands, so the grab is let go
 * on AWT's own connection first, under AWT's lock, through the toolkit's
 * internal Xlib wrapper, which the launcher already opens `sun.awt.X11` for.
 * The request itself goes out on a short connection of the launcher's own.
 *
 * Only where the window manager lists `_NET_WM_MOVERESIZE` in `_NET_SUPPORTED`;
 * off with `-Dnexira.window.systemDrag=false`. False from [begin] leaves the
 * drag to the caller, which then moves the window by hand.
 */
internal object X11SystemDrag {
    const val MOVE = 8

    private const val BUTTON_LEFT = 1L
    private const val SOURCE_APPLICATION = 1L

    private val log: Logger = LoggerFactory.getLogger(X11SystemDrag::class.java)

    private val supported: Boolean by lazy {
        val wanted = System.getProperty("nexira.window.systemDrag")?.toBooleanStrictOrNull() != false
        val declared = wanted && runCatching {
            X11Display.open()?.use { x ->
                val atom = x.atom("_NET_WM_MOVERESIZE")
                atom != 0L && (x.longs(x.root, x.atom("_NET_SUPPORTED"))?.contains(atom) ?: false)
            } ?: false
        }.getOrDefault(false)
        log.info("window drag: window manager moves and resizes it={}", declared)
        declared
    }

    /**
     * Asks the window manager to move ([MOVE]) or resize (an EWMH direction,
     * 0 top-left round to 7 left) the window whose client window is [client],
     * from where the pointer is now. False when that could not be asked.
     */
    fun begin(client: Long, direction: Int): Boolean {
        if (client == 0L || !supported) return false
        if (!releaseToolkitGrab()) return false
        return runCatching {
            X11Display.open()?.use { x ->
                val (rootX, rootY) = x.pointer() ?: return@use false
                x.sendToRoot(
                    client,
                    x.atom("_NET_WM_MOVERESIZE"),
                    longArrayOf(rootX.toLong(), rootY.toLong(), direction.toLong(), BUTTON_LEFT, SOURCE_APPLICATION),
                )
                true
            } ?: false
        }.onFailure { log.warn("Could not hand the window drag to the window manager", it) }.getOrDefault(false)
    }

    private fun releaseToolkitGrab(): Boolean = runCatching {
        val t = toolkitCalls
        t.lock.invoke()
        try {
            val display = t.display.invoke() as Long
            t.ungrab.invoke(display, CURRENT_TIME)
            t.flush.invoke(display)
        } finally {
            t.unlock.invoke()
        }
        true
    }.onFailure { log.warn("Could not let go of the toolkit's pointer grab, dragging by hand", it) }.getOrDefault(false)

    private class ToolkitCalls(
        val display: MethodHandle,
        val lock: MethodHandle,
        val unlock: MethodHandle,
        val ungrab: MethodHandle,
        val flush: MethodHandle,
    )

    /**
     * Looked up from inside `sun.awt.X11`, which the launcher opens. The lock is
     * declared on `sun.awt.SunToolkit`, a package that is not opened, so it is
     * named through `XToolkit`, which inherits it.
     */
    private val toolkitCalls: ToolkitCalls by lazy {
        val xToolkit = Class.forName("sun.awt.X11.XToolkit")
        val wrapper = Class.forName("sun.awt.X11.XlibWrapper")
        val lookup = MethodHandles.privateLookupIn(xToolkit, MethodHandles.lookup())
        val long = Long::class.javaPrimitiveType
        val void = Void.TYPE
        ToolkitCalls(
            display = lookup.findStatic(xToolkit, "getDisplay", MethodType.methodType(long)),
            lock = lookup.findStatic(xToolkit, "awtLock", MethodType.methodType(void)),
            unlock = lookup.findStatic(xToolkit, "awtUnlock", MethodType.methodType(void)),
            ungrab = lookup.findStatic(wrapper, "XUngrabPointer", MethodType.methodType(void, long, long)),
            flush = lookup.findStatic(wrapper, "XFlush", MethodType.methodType(void, long)),
        )
    }

    private const val CURRENT_TIME = 0L
}
