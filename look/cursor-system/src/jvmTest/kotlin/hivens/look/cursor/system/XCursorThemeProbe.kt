package hivens.look.cursor.system

import java.awt.Frame
import java.awt.Robot
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * That a theme's pointer reaches a window: the X server is asked which pointer it shows over it.
 *
 * Runs with `-Dnexira.probe.xcursor=1` alone, against a display of its own (`Xvfb :99`, then `DISPLAY=:99`),
 * with the theme in `XCURSOR_THEME` and `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`, which the
 * launcher itself runs with.
 */
class XCursorThemeProbe {

    @Test
    fun `the theme's hands are shown over a window`() {
        System.getProperty("nexira.probe.xcursor") ?: return
        val frame = Frame().apply {
            isUndecorated = true
            setBounds(100, 100, 200, 200)
            isVisible = true
        }
        try {
            val robot = Robot()
            listOf("grab", "grabbing", "not-allowed").forEach { name ->
                assertNotEquals(0L, XCursorTheme.cursorId(name), name)
                val icon = assertNotNull(XCursorTheme.pointer(name), name)
                frame.cursor = icon.javaClass.getMethod("getCursor").invoke(icon) as java.awt.Cursor
                robot.mouseMove(300, 350)
                robot.delay(200)
                robot.mouseMove(200, 200)
                robot.delay(400)
                println("PROBE asked $name, shown ${shownName()}")
                assertEquals(name, shownName())
            }
            assertNull(XCursorTheme.pointer("no-such-pointer-in-any-theme"))
        } finally {
            frame.dispose()
        }
    }

    /** The name of the pointer the server shows now. */
    private fun shownName(): String = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val x11 = SymbolLookup.libraryLookup("libX11.so.6", arena)
        val fixes = SymbolLookup.libraryLookup("libXfixes.so.3", arena)
        val display = linker.downcallHandle(x11.find("XOpenDisplay").get(), FunctionDescriptor.of(ADDRESS, ADDRESS)).invoke(MemorySegment.NULL) as MemorySegment
        val image = linker.downcallHandle(fixes.find("XFixesGetCursorImage").get(), FunctionDescriptor.of(ADDRESS, ADDRESS)).invoke(display) as MemorySegment
        // struct XFixesCursorImage: six shorts, the serial, the pixels, the atom and the name.
        val held = image.reinterpret(48)
        val name = held.get(ADDRESS, 40).reinterpret(256).getString(0)
        val _ = held.get(JAVA_LONG, 32)
        name
    }
}
