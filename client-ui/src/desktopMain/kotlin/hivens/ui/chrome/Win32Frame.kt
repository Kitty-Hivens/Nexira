package hivens.ui.chrome

import androidx.compose.ui.awt.ComposeWindow
import hivens.core.platform.OS
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * A native frame for the undecorated launcher window on Windows: the system
 * resizes it from its edges, moves it from the empty part of the top bar, snaps
 * it to screen edges and maximises it on a double click there, the way it does
 * for any window with a frame of its own.
 *
 * AWT creates an undecorated frame as a bare popup, and Windows offers none of
 * that to a popup: it resizes only a window with a sizing border, and a message
 * asking it to start a move or resize on the popup was answered with nothing.
 * So the window gets the sizing border, and its window procedure is wrapped
 * through an FFM upcall to answer two questions before AWT sees them:
 *
 * - WM_NCCALCSIZE: the border takes no room, so the client area is the whole
 *   window and nothing of a frame is drawn. A maximised window, which Windows
 *   places past the monitor's edges by the border's width, is cut back to the
 *   monitor's work area, whoever maximised it.
 * - WM_NCHITTEST: within [RESIZE_BORDER_DP] of an edge, that edge; on the empty
 *   part of the top bar ([CaptionRegions]), the caption; anywhere else, the
 *   client, so the bar's buttons and widgets keep getting their clicks.
 * - WM_GETMINMAXINFO: a maximised window gets the monitor's work area, where
 *   AWT gives a borderless frame the whole monitor, taskbar included.
 *
 * Everything else goes to the procedure AWT installed. A failure inside the
 * wrapper hands that message to AWT unchanged as well.
 *
 * Off with `-Dnexira.window.nativeFrame=false`, and anywhere but Windows.
 */
internal object Win32Frame {

    private val log: Logger = LoggerFactory.getLogger(Win32Frame::class.java)

    private class Installed(val previous: Long, val regions: CaptionRegions)

    private class Child(val previous: Long, val frame: Long)

    private val windows = ConcurrentHashMap<Long, Installed>()
    private val children = ConcurrentHashMap<Long, Child>()

    val enabled: Boolean by lazy {
        OS.isWindows && System.getProperty("nexira.window.nativeFrame")?.toBooleanStrictOrNull() != false
    }

    /** Wraps [window]'s procedure once. False where it is off or could not be done; the window then keeps the drawn frame. */
    fun install(window: ComposeWindow, regions: CaptionRegions): Boolean {
        if (!enabled) return false
        val hwnd = window.windowHandle
        if (hwnd == 0L) return false
        if (windows.containsKey(hwnd)) return true
        return runCatching {
            val handle = MemorySegment.ofAddress(hwnd)
            val style = F.getWindowLongPtr.invoke(handle, GWL_STYLE) as Long
            F.setWindowLongPtr.invoke(handle, GWL_STYLE, style or WS_THICKFRAME)
            val previous = F.getWindowLongPtr.invoke(handle, GWLP_WNDPROC) as Long
            windows[hwnd] = Installed(previous, regions)
            F.setWindowLongPtr.invoke(handle, GWLP_WNDPROC, upcall.address())
            wrapChildren(hwnd, hwnd)
            // Makes Windows ask WM_NCCALCSIZE again, now that it is answered here.
            F.setWindowPos.invoke(handle, MemorySegment.NULL, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE)
            log.info("window frame: native frame installed")
            true
        }.onFailure {
            windows.remove(hwnd)
            log.warn("Could not give the window a native frame, keeping the drawn one", it)
        }.getOrDefault(false)
    }

    @JvmStatic
    @Suppress("unused") // Called from native code through the upcall stub.
    fun windowProc(hwnd: MemorySegment, msg: Int, wParam: Long, lParam: Long): Long {
        val installed = windows[hwnd.address()] ?: return 0L
        try {
            when (msg) {
                WM_NCCALCSIZE -> if (wParam != 0L) {
                    if (F.isZoomed.invoke(hwnd) as Int != 0) clipToWorkArea(hwnd, lParam)
                    return 0L
                }
                WM_NCHITTEST -> return hitTest(hwnd, lParam, installed.regions)
                WM_GETMINMAXINFO -> {
                    val result = F.callWindowProc.invoke(MemorySegment.ofAddress(installed.previous), hwnd, msg, wParam, lParam) as Long
                    maximiseToWorkArea(hwnd, lParam)
                    return result
                }
                WM_PARENTNOTIFY -> if ((wParam and 0xFFFF) == WM_CREATE.toLong()) wrapChild(lParam, hwnd.address())
                WM_NCDESTROY -> windows.remove(hwnd.address())
            }
        } catch (t: Throwable) {
            log.warn("Native frame failed on message 0x{}, passing it on", msg.toString(16), t)
        }
        return F.callWindowProc.invoke(MemorySegment.ofAddress(installed.previous), hwnd, msg, wParam, lParam) as Long
    }

    /**
     * The procedure of a window inside the frame. Skia draws into a child window
     * that covers the whole client area, and Windows asks the window under the
     * pointer what a press there means, so without this the frame was never asked.
     * Where the frame's answer is an edge or the caption the child steps aside
     * with HTTRANSPARENT, and Windows asks the frame, which lives on the same thread.
     */
    @JvmStatic
    @Suppress("unused") // Called from native code through the upcall stub.
    fun childProc(hwnd: MemorySegment, msg: Int, wParam: Long, lParam: Long): Long {
        val child = children[hwnd.address()] ?: return 0L
        try {
            when (msg) {
                WM_NCHITTEST -> {
                    val frame = windows[child.frame]
                    if (frame != null && hitTest(MemorySegment.ofAddress(child.frame), lParam, frame.regions) != HT_CLIENT.toLong()) {
                        return HT_TRANSPARENT
                    }
                }
                WM_NCDESTROY -> children.remove(hwnd.address())
            }
        } catch (t: Throwable) {
            log.warn("Native frame failed on child message 0x{}, passing it on", msg.toString(16), t)
        }
        return F.callWindowProc.invoke(MemorySegment.ofAddress(child.previous), hwnd, msg, wParam, lParam) as Long
    }

    /** Wraps every window below [parent], however deep. */
    private fun wrapChildren(parent: Long, frame: Long) {
        var child = F.getWindow.invoke(MemorySegment.ofAddress(parent), GW_CHILD) as MemorySegment
        while (child.address() != 0L) {
            wrapChild(child.address(), frame)
            wrapChildren(child.address(), frame)
            child = F.getWindow.invoke(child, GW_HWNDNEXT) as MemorySegment
        }
    }

    private fun wrapChild(hwnd: Long, frame: Long) {
        if (hwnd == 0L || children.containsKey(hwnd)) return
        val handle = MemorySegment.ofAddress(hwnd)
        val previous = F.getWindowLongPtr.invoke(handle, GWLP_WNDPROC) as Long
        children[hwnd] = Child(previous, frame)
        F.setWindowLongPtr.invoke(handle, GWLP_WNDPROC, childUpcall.address())
    }

    /**
     * Sets the maximised position and size in MINMAXINFO to the work area of the
     * window's monitor. AWT answers this message first, and for a frame with no
     * border of its own it gives the whole monitor, which put a maximised window
     * over the taskbar. Its minimum and maximum tracking sizes are left as AWT set them.
     */
    private fun maximiseToWorkArea(hwnd: MemorySegment, lParam: Long) {
        val mmi = MemorySegment.ofAddress(lParam).reinterpret(MINMAXINFO_SIZE)
        Arena.ofConfined().use { a ->
            val monitor = F.monitorFromWindow.invoke(hwnd, MONITOR_DEFAULTTONEAREST) as MemorySegment
            val info = a.allocate(MONITORINFO_SIZE)
            info.set(JAVA_INT, 0, MONITORINFO_SIZE.toInt())
            if (F.getMonitorInfo.invoke(monitor, info) as Int == 0) return
            val monitorLeft = info.get(JAVA_INT, RC_MONITOR)
            val monitorTop = info.get(JAVA_INT, RC_MONITOR + 4)
            val workLeft = info.get(JAVA_INT, RC_WORK)
            val workTop = info.get(JAVA_INT, RC_WORK + 4)
            val workRight = info.get(JAVA_INT, RC_WORK + 8)
            val workBottom = info.get(JAVA_INT, RC_WORK + 12)
            // ptMaxPosition is relative to the monitor, ptMaxSize is the size.
            mmi.set(JAVA_INT, MMI_MAX_SIZE, workRight - workLeft)
            mmi.set(JAVA_INT, MMI_MAX_SIZE + 4, workBottom - workTop)
            mmi.set(JAVA_INT, MMI_MAX_POSITION, workLeft - monitorLeft)
            mmi.set(JAVA_INT, MMI_MAX_POSITION + 4, workTop - monitorTop)
        }
    }

    /** Cuts the proposed client rectangle (the first RECT of NCCALCSIZE_PARAMS) back to the monitor's work area. */
    private fun clipToWorkArea(hwnd: MemorySegment, lParam: Long) {
        val rect = MemorySegment.ofAddress(lParam).reinterpret(16)
        Arena.ofConfined().use { a ->
            val monitor = F.monitorFromWindow.invoke(hwnd, MONITOR_DEFAULTTONEAREST) as MemorySegment
            val info = a.allocate(MONITORINFO_SIZE)
            info.set(JAVA_INT, 0, MONITORINFO_SIZE.toInt())
            if (F.getMonitorInfo.invoke(monitor, info) as Int == 0) return
            for (i in 0 until 4) {
                val work = info.get(JAVA_INT, RC_WORK + i * 4L)
                val proposed = rect.get(JAVA_INT, i * 4L)
                // left and top move in, right and bottom move back.
                rect.set(JAVA_INT, i * 4L, if (i < 2) maxOf(proposed, work) else minOf(proposed, work))
            }
        }
    }

    private fun hitTest(hwnd: MemorySegment, lParam: Long, regions: CaptionRegions): Long = Arena.ofConfined().use { a ->
        val point = a.allocate(8)
        point.set(JAVA_INT, 0, lParam.toShort().toInt())
        point.set(JAVA_INT, 4, (lParam shr 16).toShort().toInt())
        F.screenToClient.invoke(hwnd, point)
        val x = point.get(JAVA_INT, 0)
        val y = point.get(JAVA_INT, 4)
        val client = a.allocate(16)
        F.getClientRect.invoke(hwnd, client)
        val width = client.get(JAVA_INT, 8)
        val height = client.get(JAVA_INT, 12)

        if (F.isZoomed.invoke(hwnd) as Int == 0 && regions.resizable) {
            val border = ((F.getDpiForWindow.invoke(hwnd) as Int) * RESIZE_BORDER_DP / 96f).roundToInt().coerceAtLeast(1)
            val left = x < border
            val right = x >= width - border
            val top = y < border
            val bottom = y >= height - border
            val edge = when {
                top && left -> HT_TOPLEFT
                top && right -> HT_TOPRIGHT
                bottom && left -> HT_BOTTOMLEFT
                bottom && right -> HT_BOTTOMRIGHT
                left -> HT_LEFT
                right -> HT_RIGHT
                top -> HT_TOP
                bottom -> HT_BOTTOM
                else -> 0
            }
            if (edge != 0) return@use edge.toLong()
        }
        if (regions.isCaption(x, y)) HT_CAPTION.toLong() else HT_CLIENT.toLong()
    }

    private val upcall: MemorySegment by lazy { procStub("windowProc") }
    private val childUpcall: MemorySegment by lazy { procStub("childProc") }

    private fun procStub(name: String): MemorySegment {
        val target = MethodHandles.lookup().findStatic(
            Win32Frame::class.java, name,
            MethodType.methodType(Long::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType),
        )
        return Linker.nativeLinker().upcallStub(target, FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), Arena.global())
    }

    /** How far in from each edge a press resizes the window, in 96-dpi pixels. */
    private const val RESIZE_BORDER_DP = 6f

    private const val GWLP_WNDPROC = -4
    private const val GWL_STYLE = -16
    private const val WS_THICKFRAME = 0x00040000L
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val WM_CREATE = 0x0001
    private const val WM_PARENTNOTIFY = 0x0210
    private const val GW_HWNDNEXT = 2
    private const val GW_CHILD = 5
    private const val HT_TRANSPARENT = -1L
    private const val WM_NCDESTROY = 0x0082
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val HT_CLIENT = 1
    private const val HT_CAPTION = 2
    private const val HT_LEFT = 10
    private const val HT_RIGHT = 11
    private const val HT_TOP = 12
    private const val HT_TOPLEFT = 13
    private const val HT_TOPRIGHT = 14
    private const val HT_BOTTOM = 15
    private const val HT_BOTTOMLEFT = 16
    private const val HT_BOTTOMRIGHT = 17
    private const val MONITOR_DEFAULTTONEAREST = 2

    private const val WM_GETMINMAXINFO = 0x0024

    // MONITORINFO: DWORD cbSize, RECT rcMonitor, RECT rcWork, DWORD dwFlags.
    private const val MONITORINFO_SIZE = 40L
    private const val RC_MONITOR = 4L
    private const val RC_WORK = 20L

    // MINMAXINFO: POINT ptReserved, ptMaxSize, ptMaxPosition, ptMinTrackSize, ptMaxTrackSize.
    private const val MINMAXINFO_SIZE = 40L
    private const val MMI_MAX_SIZE = 8L
    private const val MMI_MAX_POSITION = 16L

    /** Resolved on first use: user32 is in every Windows process already. */
    private object F {
        private val linker = Linker.nativeLinker()
        private val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
        private fun u(name: String, desc: FunctionDescriptor): MethodHandle = linker.downcallHandle(user32.find(name).orElseThrow(), desc)

        // LONG_PTR, WPARAM, LPARAM and LRESULT are 64 bits on the only Windows the launcher ships for.
        val getWindowLongPtr = u("GetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT))
        val setWindowLongPtr = u("SetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG))
        val callWindowProc = u("CallWindowProcW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))
        val setWindowPos = u("SetWindowPos", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
        val screenToClient = u("ScreenToClient", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val getClientRect = u("GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val isZoomed = u("IsZoomed", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val getDpiForWindow = u("GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val monitorFromWindow = u("MonitorFromWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        val getMonitorInfo = u("GetMonitorInfoW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val getWindow = u("GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
    }
}
