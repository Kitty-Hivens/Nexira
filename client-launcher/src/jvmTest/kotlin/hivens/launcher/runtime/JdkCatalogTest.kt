package hivens.launcher.runtime

import hivens.core.platform.Libc
import hivens.launcher.JavaManagerService.Companion.runtimeFolderName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which archive each kind of host is handed, and which it must never be handed.
 *
 * The C library is what a Linux JDK is built against: the glibc archive for a musl
 * or an Android host unpacks fine and then does not start, and that was learned from
 * `java -version` after the download. So the checks that matter are the negative
 * ones, that no host gets an archive built for another C library.
 */
class JdkCatalogTest {

    private val majors = listOf(8, 17, 21, 25)

    private fun linux(arch: String, libc: Libc) = RuntimeTarget("linux", arch, libc)

    // -- hosts that were already served: nothing moves ---------------------------

    @Test
    fun `a glibc x64 host gets the same archives it always did`() {
        assertEquals(
            listOf(
                "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-linux-amd64-full.tar.gz",
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz",
            ),
            JdkCatalog.urls(21, linux("x64", Libc.GLIBC)),
        )
    }

    @Test
    fun `an unidentified Linux is treated as glibc`() {
        for (major in majors) {
            assertEquals(JdkCatalog.urls(major, linux("x64", Libc.GLIBC)), JdkCatalog.urls(major, linux("x64", Libc.UNKNOWN)))
        }
    }

    @Test
    fun `windows and macOS ignore the C library entirely`() {
        for (libc in Libc.entries) {
            assertEquals(
                JdkCatalog.urls(21, RuntimeTarget("win", "x64", Libc.UNKNOWN)),
                JdkCatalog.urls(21, RuntimeTarget("win", "x64", libc)),
            )
            assertEquals(
                JdkCatalog.urls(25, RuntimeTarget("mac", "arm64", Libc.UNKNOWN)),
                JdkCatalog.urls(25, RuntimeTarget("mac", "arm64", libc)),
            )
        }
    }

    // -- musl --------------------------------------------------------------------

    @Test
    fun `a musl x64 host is offered musl archives for every major, and no glibc one`() {
        for (major in majors) {
            val urls = JdkCatalog.urls(major, linux("x64", Libc.MUSL))
            assertEquals(2, urls.size, "both mirrors have a musl x64 build for $major")
            assertTrue(urls[0].endsWith("-linux-x64-musl.tar.gz"), urls[0])
            assertTrue(urls[1].contains("_alpine-linux_"), urls[1])
            assertTrue(urls.none { "amd64" in it || "_x64_linux_" in it }, "no archive built for glibc: $urls")
        }
    }

    @Test
    fun `a musl arm64 host has BellSoft for every major and Adoptium only from 21`() {
        for (major in majors) {
            val urls = JdkCatalog.urls(major, linux("arm64", Libc.MUSL))
            assertTrue(urls.first().endsWith("-linux-aarch64-musl.tar.gz"), urls.first())
            val adoptium = urls.filter { "github.com/adoptium" in it }
            if (major >= 21) {
                assertEquals(1, adoptium.size, "Adoptium builds Alpine aarch64 from 21")
                assertTrue("_aarch64_alpine-linux_" in adoptium.single())
            } else {
                assertTrue(adoptium.isEmpty(), "and has none before that: $adoptium")
            }
        }
    }

    // -- bionic --------------------------------------------------------------------

    @Test
    fun `an Android host is offered nothing to download`() {
        for (major in majors) {
            assertTrue(JdkCatalog.urls(major, linux("arm64", Libc.BIONIC)).isEmpty())
            assertNull(BellSoftSource.url(major, linux("arm64", Libc.BIONIC)))
            assertNull(AdoptiumSource.url(major, linux("arm64", Libc.BIONIC)))
        }
    }

    // -- arm64 glibc, which had no entry at all ------------------------------------

    @Test
    fun `a glibc arm64 host gets aarch64 archives for every major`() {
        for (major in majors) {
            val urls = JdkCatalog.urls(major, linux("arm64", Libc.GLIBC))
            assertEquals(2, urls.size, "$major: $urls")
            assertTrue(urls[0].endsWith("-linux-aarch64.tar.gz"), urls[0])
            assertTrue("_aarch64_linux_" in urls[1], urls[1])
            assertFalse(urls.any { "musl" in it || "alpine" in it })
        }
    }

    @Test
    fun `a CPU and a Java nothing is built for gets no archive`() {
        assertTrue(JdkCatalog.urls(21, linux("x32", Libc.GLIBC)).isEmpty())
        assertTrue(JdkCatalog.urls(11, linux("x64", Libc.GLIBC)).isEmpty())
    }

    // -- what the runtime is called on disk --------------------------------------------

    @Test
    fun `a runtime keeps its folder name unless the C library is not glibc`() {
        assertEquals("java-21-linux-x64", runtimeFolderName(21, linux("x64", Libc.GLIBC)))
        assertEquals("java-21-linux-x64", runtimeFolderName(21, linux("x64", Libc.UNKNOWN)))
        assertEquals("java-21-win-x64", runtimeFolderName(21, RuntimeTarget("win", "x64", Libc.UNKNOWN)))
        assertEquals("java-21-linux-x64-musl", runtimeFolderName(21, linux("x64", Libc.MUSL)))
    }

    @Test
    fun `a target is named for a person with its C library where it matters`() {
        assertEquals("linux x64", linux("x64", Libc.GLIBC).label)
        assertEquals("linux arm64 musl", linux("arm64", Libc.MUSL).label)
        assertEquals("win x64", RuntimeTarget("win", "x64", Libc.MUSL).label)
    }
}
