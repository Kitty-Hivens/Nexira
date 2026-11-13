package hivens.window

import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener

/**
 * What [WindowHost] does to the AWT frame and hears from it, and nothing else, so
 * the host's decisions can be tested against a frame that is not on a screen.
 */
internal interface FrameOps {
    var extendedState: Int
    var bounds: Rectangle
    val showing: Boolean

    /** The work area of the screen the frame is on now. */
    val workArea: Rectangle

    /** The bounds of the screen the frame is on now, or null before the peer knows. */
    val screenBounds: Rectangle?

    /** Which screen the frame is on, to notice it crossing to another. */
    val screenId: String?

    /** Whether the toolkit can maximise a frame at all. */
    val maximizeSupported: Boolean

    /** Whether the frame holds the keyboard focus now. */
    val focused: Boolean

    fun setMaximizedBounds(bounds: Rectangle?)
    fun setMinimumSize(size: Dimension)

    /**
     * Brings the frame to the front and asks for the focus. A plain toFront is
     * ignored by most X11 window managers to stop focus stealing (KDE, Hyprland and
     * GNOME all do), so the frame is put on top for the moment of the call, which
     * every one of them honours.
     */
    fun raise()

    /** Calls [listener] with the old and new extended state on every change. Returns the removal. */
    fun onStateChanged(listener: (old: Int, new: Int) -> Unit): () -> Unit

    /** Calls [listener] on every move and resize. Returns the removal. */
    fun onGeometryChanged(listener: () -> Unit): () -> Unit

    /** Calls [listener] with true when the frame gains the focus and false when it loses it. Returns the removal. */
    fun onFocusChanged(listener: (Boolean) -> Unit): () -> Unit

    /** Runs [block] on the AWT event thread, after what is queued there now. */
    fun later(block: () -> Unit)
}

internal class AwtFrameOps(private val frame: Frame) : FrameOps {
    override var extendedState: Int
        get() = frame.extendedState
        set(value) { frame.extendedState = value }

    override var bounds: Rectangle
        get() = frame.bounds
        set(value) { frame.bounds = value }

    override val showing: Boolean get() = frame.isShowing
    override val workArea: Rectangle get() = screenWorkArea(frame.graphicsConfiguration)

    override val screenBounds: Rectangle?
        get() = frame.graphicsConfiguration?.bounds?.takeIf { it.width > 0 && it.height > 0 }

    override val screenId: String? get() = frame.graphicsConfiguration?.device?.iDstring

    override val maximizeSupported: Boolean
        get() = Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH)

    override val focused: Boolean get() = frame.isFocused

    override fun setMaximizedBounds(bounds: Rectangle?) {
        frame.maximizedBounds = bounds
    }

    override fun setMinimumSize(size: Dimension) {
        frame.minimumSize = size
    }

    override fun raise() {
        frame.isAlwaysOnTop = true
        frame.toFront()
        frame.requestFocus()
        frame.isAlwaysOnTop = false
    }

    override fun onFocusChanged(listener: (Boolean) -> Unit): () -> Unit {
        val l = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = listener(true)
            override fun windowLostFocus(e: WindowEvent) = listener(false)
        }
        frame.addWindowFocusListener(l)
        return { frame.removeWindowFocusListener(l) }
    }

    override fun onStateChanged(listener: (old: Int, new: Int) -> Unit): () -> Unit {
        val l = WindowStateListener { e -> listener(e.oldState, e.newState) }
        frame.addWindowStateListener(l)
        return { frame.removeWindowStateListener(l) }
    }

    override fun onGeometryChanged(listener: () -> Unit): () -> Unit {
        val l = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = listener()
            override fun componentResized(e: ComponentEvent) = listener()
        }
        frame.addComponentListener(l)
        return { frame.removeComponentListener(l) }
    }

    override fun later(block: () -> Unit) = EventQueue.invokeLater(block)
}
