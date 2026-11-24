package hivens.look.cursor.system

import androidx.compose.ui.input.pointer.PointerIcon
import org.slf4j.LoggerFactory
import java.awt.Cursor
import java.awt.Toolkit
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * The pointers of the person's cursor theme under X11, by name, through libXcursor.
 *
 * AWT offers fourteen pointers and no way to ask for another, and a pointer made from a picture it cuts
 * down to two colours without transparency. A theme's pointer is loaded over a connection of the launcher's
 * own, and the server keeps it for as long as that connection is open, which is until the launcher exits.
 * Its id is put into an AWT cursor, which AWT then sets like any it made. Everything here that is not there,
 * another toolkit, no library, no such pointer in the theme, answers null, and the role is left alone.
 */
internal object XCursorTheme {
    private val log = LoggerFactory.getLogger("hivens.look.cursor.XCursorTheme")

    /** The theme's pointer called [name], or null where there is none here. */
    fun pointer(name: String): PointerIcon? = try {
        if (Toolkit.getDefaultToolkit().javaClass.name != X_TOOLKIT) null
        else cursorId(name).takeIf { it != 0L }?.let { PointerIcon(awtCursor(it)) }
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        log.info("The cursor theme's '{}' is not used: {}", name, e.toString())
        null
    }

    /** The server's id of the theme's pointer called [name], 0 where the theme has none. */
    internal fun cursorId(name: String): Long = Arena.ofConfined().use { arena ->
        val display = Native.display
        if (display.address() == 0L) return@use 0L
        val id = Native.loadCursor.invoke(display, arena.allocateFrom(name)) as Long
        // The server has to know the pointer before AWT names it over its own connection.
        val _ = Native.sync.invoke(display, 0) as Int
        id
    }

    private fun awtCursor(id: Long): Cursor {
        val cursor = Cursor(Cursor.DEFAULT_CURSOR)
        val set = Class.forName(X_CURSOR_MANAGER).getDeclaredMethod("setPData", Cursor::class.java, Long::class.javaPrimitiveType)
        set.isAccessible = true
        set.invoke(null, cursor, id)
        return cursor
    }

    private object Native {
        private val linker = Linker.nativeLinker()
        private val x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global())
        private val xcursor = SymbolLookup.libraryLookup("libXcursor.so.1", Arena.global())

        val loadCursor: MethodHandle = handle(xcursor, "XcursorLibraryLoadCursor", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS))
        val sync: MethodHandle = handle(x11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

        /** Held open for the life of the process: closing it would take the pointers with it. */
        val display: MemorySegment =
            handle(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS)).invoke(MemorySegment.NULL) as MemorySegment

        private fun handle(library: SymbolLookup, name: String, type: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(library.find(name).orElseThrow { NoSuchElementException(name) }, type)
    }

    private const val X_TOOLKIT = "sun.awt.X11.XToolkit"
    private const val X_CURSOR_MANAGER = "sun.awt.X11.XGlobalCursorManager"
}
