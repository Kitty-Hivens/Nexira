package hivens.window

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner of the window's state against a frame that is not on a screen: one
 * source per fact, commands that go to the frame and never into the state, and
 * the first restore of a window born maximised.
 */
class WindowHostTest {

    /** A frame that does what it is told at once, the way Windows and macOS do. */
    private class FakeFrame(state: Int = 0, var frameBounds: Rectangle = Rectangle(0, 0, 1920, 1032)) : FrameOps {
        private val stateListeners = ArrayList<(Int, Int) -> Unit>()
        private val geometryListeners = ArrayList<() -> Unit>()
        private val queued = ArrayList<() -> Unit>()
        var minimum: Dimension? = null
        var screen: String = "screen-1"
        var obeys = true

        private var state = state
        override var extendedState: Int
            get() = state
            set(value) {
                if (!obeys) return
                val old = state
                state = value
                stateListeners.toList().forEach { it(old, value) }
            }

        override var bounds: Rectangle
            get() = Rectangle(frameBounds)
            set(value) {
                frameBounds = Rectangle(value)
                geometryListeners.toList().forEach { it() }
            }

        override val showing = true
        override val workArea = Rectangle(0, 0, 1920, 1032)
        override val screenBounds = Rectangle(0, 0, 1920, 1080)
        override val screenId: String get() = screen
        override val maximizeSupported = true
        override fun setMaximizedBounds(bounds: Rectangle?) {}
        override fun setMinimumSize(size: Dimension) { minimum = size }

        override fun onStateChanged(listener: (old: Int, new: Int) -> Unit): () -> Unit {
            stateListeners += listener
            return { stateListeners -= listener }
        }

        override fun onGeometryChanged(listener: () -> Unit): () -> Unit {
            geometryListeners += listener
            return { geometryListeners -= listener }
        }

        override fun later(block: () -> Unit) { queued += block }

        /** Runs what was queued for the event thread, and what that queued in turn. */
        fun drain() {
            while (queued.isNotEmpty()) {
                val next = queued.toList()
                queued.clear()
                next.forEach { it() }
            }
        }
    }

    /** A system that reports maximised on its own, the way Windows' frame and an X11 window manager do. */
    private class ReportingBackend(actions: WindowActions = WindowActions.Everything) : Backend {
        override val actions: StateFlow<WindowActions> = MutableStateFlow(actions)
        override val captions: CaptionRegions? = null
        override val nativeFrame = true
        lateinit var report: (Boolean) -> Unit
        override fun start(report: (Boolean) -> Unit) { this.report = report }
    }

    private fun host(frame: FakeFrame, backend: Backend = PlainBackend(WindowActions.Everything), firstRestore: Rectangle? = null) =
        WindowHost(frame, backend, firstRestore).also { it.start() }

    @Test
    fun `where nothing else answers the frame's extended state is the state`() {
        val frame = FakeFrame()
        val host = host(frame)
        assertEquals(WindowStatus(maximized = false, minimized = false), host.status.value)
        host.maximize()
        assertTrue(host.status.value.maximized)
        host.minimize()
        assertEquals(WindowStatus(maximized = true, minimized = true), host.status.value)
        host.unminimize()
        host.restore()
        assertEquals(WindowStatus(maximized = false, minimized = false), host.status.value)
    }

    @Test
    fun `the system's own word on maximised wins over the frame's`() {
        val frame = FakeFrame()
        val backend = ReportingBackend()
        val host = host(frame, backend)
        // AWT sets the bit the moment it is asked, before the window manager has answered.
        host.maximize()
        backend.report(false)
        frame.drain()
        assertFalse(host.status.value.maximized, "the window manager has not maximised it")
        backend.report(true)
        frame.drain()
        assertTrue(host.status.value.maximized)
    }

    @Test
    fun `a click before the system answers repeats the request instead of reversing it`() {
        val frame = FakeFrame()
        val backend = ReportingBackend()
        val host = host(frame, backend)
        backend.report(false)
        frame.drain()
        host.toggleMaximize()
        host.toggleMaximize()
        assertTrue(frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH, "both clicks asked to maximise")
    }

    @Test
    fun `a command never changes the state by itself`() {
        val frame = FakeFrame().apply { obeys = false }
        val host = host(frame)
        host.maximize()
        assertFalse(host.status.value.maximized, "a system that ignored the request leaves the button where it was")
    }

    @Test
    fun `a window born maximised is given its saved frame on its first restore, and only then`() {
        val saved = Rectangle(200, 100, 1200, 800)
        val frame = FakeFrame(state = Frame.MAXIMIZED_BOTH)
        val host = host(frame, firstRestore = saved)
        host.restore()
        frame.drain()
        assertEquals(saved, frame.frameBounds)

        frame.frameBounds = Rectangle(300, 300, 1000, 700)
        host.maximize()
        host.restore()
        frame.drain()
        assertEquals(Rectangle(300, 300, 1000, 700), frame.frameBounds, "later restores are the system's")
    }

    @Test
    fun `a window that opened as a window keeps the size it was left at`() {
        val frame = FakeFrame(frameBounds = Rectangle(200, 100, 1200, 800))
        val host = host(frame, firstRestore = Rectangle(0, 0, 900, 600))
        host.maximize()
        frame.frameBounds = Rectangle(0, 0, 1920, 1032)
        frame.extendedState = 0
        frame.drain()
        assertEquals(Rectangle(0, 0, 1920, 1032), frame.frameBounds, "no first-restore size for a window that had one")
    }

    @Test
    fun `a window manager that will not resize the window is never handed a size`() {
        val frame = FakeFrame(state = Frame.MAXIMIZED_BOTH)
        val host = host(frame, PlainBackend(WindowActions.Everything.copy(resize = false)), firstRestore = Rectangle(1, 2, 3, 4))
        host.restore()
        frame.drain()
        assertEquals(Rectangle(0, 0, 1920, 1032), frame.frameBounds)
    }

    @Test
    fun `dragging a maximised window out keeps the pointer at the same share of the bar`() {
        val frame = FakeFrame(state = Frame.MAXIMIZED_BOTH)
        val host = host(frame, firstRestore = Rectangle(200, 100, 1000, 700))
        host.unmaximizeUnderCursor(pointerX = 960, pointerY = 10)
        assertEquals(Rectangle(960 - 500, 0, 1000, 700), frame.frameBounds)
        assertFalse(host.status.value.maximized)
    }

    @Test
    fun `the minimum size is cut to the screen and worked out again on another screen`() {
        val frame = FakeFrame()
        val host = host(frame)
        host.keepMinimumSize(Dimension(960, 600))
        frame.drain()
        assertEquals(Dimension(960, 600), frame.minimum)

        frame.minimum = null
        frame.bounds = Rectangle(10, 10, 1200, 800)
        frame.drain()
        assertEquals(null, frame.minimum, "a move on the same screen changes nothing")

        frame.screen = "screen-2"
        frame.bounds = Rectangle(2000, 10, 1200, 800)
        frame.drain()
        assertEquals(Dimension(960, 600), frame.minimum)
    }

    @Test
    fun `the session records the normal frame and never a maximised or minimised one`() {
        val frame = FakeFrame(frameBounds = Rectangle(200, 100, 1200, 800))
        val host = host(frame)
        val written = CopyOnWriteArrayList<SavedWindow>()
        val recorder = WindowSessionRecorder(host, null) { written += it }
        assertEquals(SavedWindow(false, 200, 100, 1200, 800), recorder.snapshot())

        host.maximize()
        frame.frameBounds = Rectangle(0, 0, 1920, 1032)
        assertEquals(SavedWindow(true, 200, 100, 1200, 800), recorder.snapshot(), "the normal frame stays the one before")

        host.minimize()
        frame.frameBounds = Rectangle(-32000, -32000, 160, 28)
        assertEquals(SavedWindow(true, 200, 100, 1200, 800), recorder.snapshot())

        recorder.close()
        assertEquals(SavedWindow(true, 200, 100, 1200, 800), written.lastOrNull(), "closing writes in place")
    }
}
