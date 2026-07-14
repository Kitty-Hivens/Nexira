package hivens.ui.bootstrap

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * Best-effort "is Shift held at launch?" probe -- the UEFI-style hold-to-recover
 * gesture. Opens its OWN short-lived X11 connection via Panama (libX11
 * `XOpenDisplay` / `XQueryKeymap`), so it needs neither the AWT toolkit (no timing
 * dependency on when the shell's window comes up) nor any `sun.awt.X11` reflection,
 * and it works wherever the launcher already runs as an X client -- native X and
 * XWayland alike.
 *
 * Everything is swallowed: no X server, no libX11, native Wayland with no XWayland
 * (`XOpenDisplay` returns NULL), or any error at all -> `false`, and the other
 * recovery-entry signals (env / --recovery / marker) still stand. Linux-only;
 * a no-op elsewhere. `--enable-native-access=ALL-UNNAMED` is already on the JVM.
 */
object HoldKeyProbe {

    private const val XK_SHIFT_L = 0xFFE1L
    private const val XK_SHIFT_R = 0xFFE2L

    fun shiftHeld(): Boolean = runCatching { probe() }.getOrDefault(false)

    private fun probe(): Boolean {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return false
        if (System.getenv("DISPLAY").isNullOrBlank()) return false // no X server reachable
        Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val x11 = SymbolLookup.libraryLookup("libX11.so.6", arena)
            fun handle(name: String, desc: FunctionDescriptor) = linker.downcallHandle(x11.find(name).get(), desc)

            val open = handle("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
            val close = handle("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS))
            val keysymToKeycode = handle("XKeysymToKeycode", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_LONG))
            val queryKeymap = handle("XQueryKeymap", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

            val display = open.invoke(MemorySegment.NULL) as MemorySegment
            if (display.address() == 0L) return false // no X server / no XWayland
            try {
                val keys = arena.allocate(32)
                queryKeymap.invoke(display, keys)
                val keymap = keys.toArray(JAVA_BYTE)
                val left = (keysymToKeycode.invoke(display, XK_SHIFT_L) as Byte).toInt() and 0xFF
                val right = (keysymToKeycode.invoke(display, XK_SHIFT_R) as Byte).toInt() and 0xFF
                return shiftHeldInKeymap(keymap, listOf(left, right))
            } finally {
                close.invoke(display)
            }
        }
    }

    /** The X11 keymap is 32 bytes = 256 bits; bit `keycode` is set iff that key is down. */
    internal fun shiftHeldInKeymap(keymap: ByteArray, keycodes: List<Int>): Boolean =
        keycodes.any { kc -> kc in 1..255 && ((keymap[kc / 8].toInt() and 0xFF) and (1 shl (kc % 8))) != 0 }
}
