package hivens.launcher.runtime

import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.Transfer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runtime a phone is given must be exactly the pinned build, in one piece, or
 * the one it already had. The checks that matter are the failing ones: whatever goes
 * wrong while installing a new build, the old one is still where it was.
 */
class AndroidJdkInstallerTest {

    @TempDir
    lateinit var tmp: Path

    private val sha = "a".repeat(64)

    private fun release(version: String = "25.0.5") = AndroidJdkRelease(25, version, "https://example.invalid/jdk.zip", sha)

    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun runtimeZip(version: String = "25.0.5", top: String? = "jdk-$version-android-aarch64", marker: String = "new"): ByteArray {
        val prefix = top?.let { "$it/" }.orEmpty()
        return zip(
            mapOf(
                "${prefix}release" to "IMPLEMENTOR=\"N/A\"\nJAVA_VERSION=\"$version\"\nOS_ARCH=\"aarch64\"\n",
                "${prefix}lib/server/libjvm.so" to "vm $marker",
                "${prefix}lib/modules" to "modules",
            ),
        )
    }

    /** Stands in for the transfer engine: writes [archive] where the transfer says. */
    private inner class FakeFetch(var archive: ByteArray) {
        val transfers = ArrayList<Transfer>()
        var failWith: Exception? = null
        var delayMs = 0L

        val fetch: suspend (Transfer, (Long, Long) -> Unit) -> Unit = { transfer, progress ->
            transfers += transfer
            failWith?.let { throw it }
            if (delayMs > 0) delay(delayMs)
            Files.createDirectories(transfer.dest.parent)
            Files.write(transfer.dest, archive)
            progress(archive.size.toLong(), archive.size.toLong())
        }
    }

    private fun installer(fake: FakeFetch) = AndroidJdkInstaller(tmp.resolve("runtimes"), fake.fetch)

    private val home: Path get() = tmp.resolve("runtimes").resolve(AndroidJdkInstaller.folderName(25))

    private fun installOld(version: String = "25.0.4") {
        Files.createDirectories(home.resolve("lib/server"))
        Files.writeString(home.resolve("lib/server/libjvm.so"), "vm old")
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$version\"\n")
    }

    private fun libjvm(): String = Files.readString(home.resolve("lib/server/libjvm.so"))

    @Test
    fun `a missing runtime is fetched against the pinned digest and unpacked without the archive's top folder`() = runBlocking {
        val fake = FakeFetch(runtimeZip())
        val result = installer(fake).ensure(release())

        assertEquals(home, result)
        assertEquals("vm new", libjvm())
        assertTrue(Files.isRegularFile(home.resolve("lib/modules")))
        assertEquals(1, fake.transfers.size)
        val transfer = fake.transfers.single()
        assertEquals("https://example.invalid/jdk.zip", transfer.url)
        assertEquals(Digest(DigestAlgorithm.SHA256, sha), transfer.expect)
        assertFalse(Files.exists(transfer.dest), "the archive is not kept")
        assertFalse(Files.exists(home.resolveSibling("${home.fileName}.incoming")))
    }

    @Test
    fun `an archive with no top folder installs the same`() = runBlocking {
        installer(FakeFetch(runtimeZip(top = null))).ensure(release())
        assertEquals("vm new", libjvm())
        assertEquals("25.0.5", Files.readAllLines(home.resolve("release")).first { it.startsWith("JAVA_VERSION") }.substringAfter('"').trim('"'))
    }

    @Test
    fun `an installed runtime of the pinned version is used without a download`() = runBlocking {
        installOld("25.0.5")
        val fake = FakeFetch(runtimeZip())
        installer(fake).ensure(release("25.0.5"))
        assertTrue(fake.transfers.isEmpty())
        assertEquals("vm old", libjvm())
    }

    @Test
    fun `a runtime of another version is replaced, and nothing of it is left`() = runBlocking {
        installOld("25.0.4")
        Files.writeString(home.resolve("stale.txt"), "from the old build")
        installer(FakeFetch(runtimeZip("25.0.5"))).ensure(release("25.0.5"))
        assertEquals("vm new", libjvm())
        assertFalse(Files.exists(home.resolve("stale.txt")))
        assertFalse(Files.exists(home.resolveSibling("${home.fileName}.previous")))
    }

    @Test
    fun `an entry that would land outside the target is refused and the installed runtime stays`() {
        installOld("25.0.4")
        val evil = zip(mapOf("../escape.txt" to "x", "jdk/lib/server/libjvm.so" to "vm evil", "jdk/release" to "JAVA_VERSION=\"25.0.5\"\n"))
        assertFailsWith<IOException> { runBlocking { installer(FakeFetch(evil)).ensure(release("25.0.5")) } }
        assertEquals("vm old", libjvm())
        assertFalse(Files.exists(tmp.resolve("runtimes").resolve("escape.txt")))
        assertFalse(Files.exists(home.resolveSibling("${home.fileName}.incoming")))
    }

    @Test
    fun `an archive with no runtime in it is refused and the installed runtime stays`() {
        installOld("25.0.4")
        val empty = zip(mapOf("jdk/readme.txt" to "nothing here"))
        assertFailsWith<IOException> { runBlocking { installer(FakeFetch(empty)).ensure(release("25.0.5")) } }
        assertEquals("vm old", libjvm())
    }

    @Test
    fun `an archive of another version than the pin is refused and the installed runtime stays`() {
        installOld("25.0.4")
        assertFailsWith<IOException> { runBlocking { installer(FakeFetch(runtimeZip("25.0.6"))).ensure(release("25.0.5")) } }
        assertEquals("vm old", libjvm())
    }

    @Test
    fun `a download that fails leaves the installed runtime and the error reaches the caller`() {
        installOld("25.0.4")
        val fake = FakeFetch(runtimeZip()).apply { failWith = IOException("every source failed") }
        val error = assertFailsWith<IOException> { runBlocking { installer(fake).ensure(release("25.0.5")) } }
        assertEquals("every source failed", error.message)
        assertEquals("vm old", libjvm())
    }

    @Test
    fun `a runtime an unfinished swap left aside is put back and used`() = runBlocking {
        val previous = home.resolveSibling("${home.fileName}.previous")
        Files.createDirectories(previous.resolve("lib/server"))
        Files.writeString(previous.resolve("lib/server/libjvm.so"), "vm rescued")
        Files.writeString(previous.resolve("release"), "JAVA_VERSION=\"25.0.5\"\n")
        val fake = FakeFetch(runtimeZip())
        installer(fake).ensure(release("25.0.5"))
        assertTrue(fake.transfers.isEmpty())
        assertEquals("vm rescued", libjvm())
    }

    @Test
    fun `two requests for one runtime fetch it once`() = runBlocking {
        val fake = FakeFetch(runtimeZip()).apply { delayMs = 150 }
        val installer = installer(fake)
        val homes = listOf(async { installer.ensure(release()) }, async { installer.ensure(release()) }).awaitAll()
        assertEquals(listOf(home, home), homes)
        assertEquals(1, fake.transfers.size)
    }

    @Test
    fun `progress is reported in steps and ends ready`() = runBlocking {
        val said = ArrayList<String>()
        installer(FakeFetch(runtimeZip())).ensure(release()) { said += it }
        assertEquals(listOf("Downloading Java 25...", "Downloading Java 25: 100%", "Unpacking Java 25...", "Java 25 ready"), said)
    }

    // -- the catalog ---------------------------------------------------------------

    @Test
    fun `a pin must be a lowercase SHA-256`() {
        assertFailsWith<IllegalArgumentException> { AndroidJdkRelease(25, "25.0.5", "u", "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { AndroidJdkRelease(25, "25.0.5", "u", "ab") }
    }

    @Test
    fun `a catalog answers by major and refuses two builds for one`() {
        val catalog = AndroidJdkCatalog(listOf(release()))
        assertEquals(release(), catalog.forMajor(25))
        assertEquals(null, catalog.forMajor(8))
        assertEquals(setOf(25), catalog.majors)
        assertFailsWith<IllegalArgumentException> { AndroidJdkCatalog(listOf(release(), release("25.0.6"))) }
    }

    @Test
    fun `an archive is published under the name the build script gives it`() {
        assertEquals(
            "https://github.com/Kitty-Hivens/nexira-android-jdk/releases/download/jdk-25.0.5/jdk-25.0.5-android-aarch64.zip",
            AndroidJdkCatalog.assetUrl("25.0.5"),
        )
    }
}
