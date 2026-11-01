package hivens.ui.chrome

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * A connection of the launcher's own to the X server, for reading what the window
 * manager publishes about windows: the handful of Xlib calls that needs, through
 * Panama, the way [hivens.ui.bootstrap.HoldKeyProbe] reads the keymap.
 *
 * Its own connection rather than AWT's, because AWT exposes no way to read a
 * property it did not ask for. Event masks are per connection, so selecting
 * property changes here leaves AWT's own event stream as it was.
 *
 * Not thread-safe. One thread at a time, which is how Xlib wants a connection
 * that was opened without XInitThreads, and that call is not ours to make inside
 * a process where AWT already speaks Xlib.
 *
 * Window and Atom are C `unsigned long`, 64 bits on every Linux the launcher runs
 * on, and format-32 property data comes back from Xlib as an array of `long`.
 */
internal class X11Display private constructor(private val display: MemorySegment) : AutoCloseable {

    val root: Long = F.defaultRootWindow.invoke(display) as Long

    /** The atom for [name], or 0 when the server has never heard of it, so no window can carry it. */
    fun atom(name: String): Long = Arena.ofConfined().use { a ->
        F.internAtom.invoke(display, a.allocateFrom(name), 1) as Long
    }

    /** The format-32 values of [property] on [window], or null when the window does not carry it. */
    fun longs(window: Long, property: Long): LongArray? = readProperty(window, property) { type, format, items, data ->
        if (type == 0L || format != 32) null else data.reinterpret(items * 8).toArray(JAVA_LONG)
    }

    /** Whether [window] carries [property] at all, whatever its type. */
    fun has(window: Long, property: Long): Boolean = readProperty(window, property) { type, _, _, _ -> type != 0L } ?: false

    /** The parent of [window], or 0 when the server does not know the window. */
    fun parentOf(window: Long): Long = queryTree(window)?.first ?: 0L

    /** The children of [window], bottom of the stack first, or none when the server does not know the window. */
    fun childrenOf(window: Long): LongArray = queryTree(window)?.second ?: LongArray(0)

    private fun queryTree(window: Long): Pair<Long, LongArray>? = Arena.ofConfined().use { a ->
        val rootOut = a.allocate(JAVA_LONG)
        val parentOut = a.allocate(JAVA_LONG)
        val childrenOut = a.allocate(ADDRESS)
        val countOut = a.allocate(JAVA_INT)
        val ok = F.queryTree.invoke(display, window, rootOut, parentOut, childrenOut, countOut) as Int
        val children = childrenOut.get(ADDRESS, 0)
        try {
            if (ok == 0) return@use null
            val count = countOut.get(JAVA_INT, 0).toLong()
            val list = if (count == 0L || children.address() == 0L) LongArray(0) else children.reinterpret(count * 8).toArray(JAVA_LONG)
            parentOut.get(JAVA_LONG, 0) to list
        } finally {
            if (children.address() != 0L) F.free.invoke(children)
        }
    }

    /** Asks for a PropertyNotify on this connection whenever a property of [window] changes. */
    fun watchProperties(window: Long) {
        F.selectInput.invoke(display, window, PROPERTY_CHANGE_MASK)
    }

    /**
     * Waits up to [timeoutMs] for events and returns the properties that changed,
     * as (window, atom) pairs. Empty on a timeout, which is what lets the caller
     * look at whether it was asked to stop: a thread parked in XNextEvent could
     * not be woken without writing to this connection from another thread.
     */
    fun awaitPropertyChanges(timeoutMs: Int): List<Pair<Long, Long>> {
        if (F.pending.invoke(display) as Int == 0) {
            Arena.ofConfined().use { a ->
                val pollFd = a.allocate(8)
                pollFd.set(JAVA_INT, 0, F.connectionNumber.invoke(display) as Int)
                pollFd.set(JAVA_SHORT, 4, POLLIN)
                F.poll.invoke(pollFd, 1L, timeoutMs)
            }
        }
        val changed = ArrayList<Pair<Long, Long>>()
        Arena.ofConfined().use { a ->
            val event = a.allocate(XEVENT_SIZE)
            while (F.pending.invoke(display) as Int > 0) {
                F.nextEvent.invoke(display, event)
                if (event.get(JAVA_INT, 0) == PROPERTY_NOTIFY) {
                    changed += event.get(JAVA_LONG, XPROPERTY_WINDOW) to event.get(JAVA_LONG, XPROPERTY_ATOM)
                }
            }
        }
        return changed
    }

    override fun close() {
        F.closeDisplay.invoke(display)
    }

    private inline fun <T> readProperty(
        window: Long,
        property: Long,
        read: (type: Long, format: Int, items: Long, data: MemorySegment) -> T?,
    ): T? {
        if (property == 0L) return null
        return Arena.ofConfined().use { a ->
            val typeOut = a.allocate(JAVA_LONG)
            val formatOut = a.allocate(JAVA_INT)
            val itemsOut = a.allocate(JAVA_LONG)
            val afterOut = a.allocate(JAVA_LONG)
            val dataOut = a.allocate(ADDRESS)
            val status = F.getWindowProperty.invoke(
                display, window, property, 0L, MAX_PROPERTY_LONGS, 0, ANY_PROPERTY_TYPE,
                typeOut, formatOut, itemsOut, afterOut, dataOut,
            ) as Int
            if (status != 0) return@use null
            val data = dataOut.get(ADDRESS, 0)
            try {
                read(typeOut.get(JAVA_LONG, 0), formatOut.get(JAVA_INT, 0), itemsOut.get(JAVA_LONG, 0), data)
            } finally {
                if (data.address() != 0L) F.free.invoke(data)
            }
        }
    }

    companion object {
        /** A connection to the display in `DISPLAY`, or null when there is none to reach. */
        fun open(): X11Display? {
            if (System.getenv("DISPLAY").isNullOrBlank()) return null
            val display = runCatching { F.openDisplay.invoke(MemorySegment.NULL) as MemorySegment }.getOrNull() ?: return null
            return if (display.address() == 0L) null else X11Display(display)
        }

        private const val ANY_PROPERTY_TYPE = 0L
        private const val MAX_PROPERTY_LONGS = 1024L
        private const val PROPERTY_CHANGE_MASK = 1L shl 22
        private const val PROPERTY_NOTIFY = 28
        private const val POLLIN: Short = 1

        // XEvent is a 24-long union. XPropertyEvent within it: int type, unsigned
        // long serial, Bool send_event, Display *display, Window window, Atom atom.
        private const val XEVENT_SIZE = 24L * 8
        private const val XPROPERTY_WINDOW = 32L
        private const val XPROPERTY_ATOM = 40L
    }

    /** Resolved once: libX11 is already in the process for AWT, so this only finds it. */
    private object F {
        private val linker = Linker.nativeLinker()
        private val x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global())
        private fun x(name: String, desc: FunctionDescriptor): MethodHandle = linker.downcallHandle(x11.find(name).orElseThrow(), desc)

        val openDisplay = x("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val closeDisplay = x("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val defaultRootWindow = x("XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
        val internAtom = x("XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT))
        val getWindowProperty = x(
            "XGetWindowProperty",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
            ),
        )
        val free = x("XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val queryTree = x("XQueryTree", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        val selectInput = x("XSelectInput", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG))
        val pending = x("XPending", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val nextEvent = x("XNextEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val connectionNumber = x("XConnectionNumber", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val poll: MethodHandle = linker.downcallHandle(
            linker.defaultLookup().find("poll").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
        )
    }
}
