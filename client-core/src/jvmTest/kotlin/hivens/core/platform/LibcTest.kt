package hivens.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class LibcTest {

    private val glibcMap = """
        7f1c00000000-7f1c00028000 r--p 00000000 fd:01 1 /usr/lib/ld-linux-x86-64.so.2
        7f1c00100000-7f1c00250000 r-xp 00000000 fd:01 2 /usr/lib/libc.so.6
    """.trimIndent()

    private val muslMap = """
        7f9a00000000-7f9a00060000 r-xp 00000000 fd:01 1 /lib/ld-musl-x86_64.so.1
        7f9a00100000-7f9a00150000 r-xp 00000000 fd:01 2 /usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so
    """.trimIndent()

    private val bionicMap = """
        7a00000000-7a00060000 r-xp 00000000 07:20 1 /apex/com.android.runtime/lib64/bionic/libc.so
        7a00100000-7a00150000 r-xp 00000000 07:20 2 /system/bin/linker64
    """.trimIndent()

    @Test
    fun `a glibc loader and libc in the map is glibc`() {
        assertEquals(Libc.GLIBC, Libc.classify(Platform.LINUX, glibcMap))
    }

    @Test
    fun `a musl loader in the map is musl`() {
        assertEquals(Libc.MUSL, Libc.classify(Platform.LINUX, muslMap))
    }

    @Test
    fun `bionic is named by its own paths`() {
        assertEquals(Libc.BIONIC, Libc.classify(Platform.LINUX, bionicMap))
    }

    @Test
    fun `the process decides and not what else is installed`() {
        // Alpine with a glibc compatibility layer holds both loaders on disk, but the
        // map of a process running on musl names only the one it loaded.
        assertEquals(Libc.MUSL, Libc.classify(Platform.LINUX, muslMap))
    }

    @Test
    fun `an unreadable map or a map that names none is unknown`() {
        assertEquals(Libc.UNKNOWN, Libc.classify(Platform.LINUX, null))
        assertEquals(Libc.UNKNOWN, Libc.classify(Platform.LINUX, ""))
        assertEquals(Libc.UNKNOWN, Libc.classify(Platform.LINUX, "7f00-7f10 r-xp 0 0:0 0 /opt/something/libfoo.so"))
    }

    @Test
    fun `windows and macOS have no libc axis`() {
        assertEquals(Libc.UNKNOWN, Libc.classify(Platform.WINDOWS, glibcMap))
        assertEquals(Libc.UNKNOWN, Libc.classify(Platform.MACOS, glibcMap))
    }
}
