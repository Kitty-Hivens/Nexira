package hivens.launcher

import hivens.test.buildMockClient
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JavaManagerServiceTest {

    private lateinit var workDir: Path
    private lateinit var svc: JavaManagerService

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-jm-test-")
        svc = JavaManagerService(workDir, buildMockClient(""))
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── detectJavaVersion: MC version -> Java major ───────────────────────

    @Test
    fun `detectJavaVersion maps 1_7_10 to Java 8`() {
        assertEquals(8, svc.detectJavaVersion("1.7.10"))
    }

    @Test
    fun `detectJavaVersion maps 1_12_2 to Java 8 (legacy LWJGL2)`() {
        assertEquals(8, svc.detectJavaVersion("1.12.2"))
    }

    @Test
    fun `detectJavaVersion maps 1_17 to Java 17`() {
        assertEquals(17, svc.detectJavaVersion("1.17.1"))
    }

    @Test
    fun `detectJavaVersion maps 1_18 1_19 1_20 (early) to Java 17`() {
        assertEquals(17, svc.detectJavaVersion("1.18.2"))
        assertEquals(17, svc.detectJavaVersion("1.19.4"))
        assertEquals(17, svc.detectJavaVersion("1.20.4"))
    }

    @Test
    fun `detectJavaVersion maps 1_20_5 onwards to Java 21`() {
        // The 1.20.5/1.20.6 carve-out -- Mojang bumped Java required mid-release
        assertEquals(21, svc.detectJavaVersion("1.20.5"))
        assertEquals(21, svc.detectJavaVersion("1.20.6"))
    }

    @Test
    fun `detectJavaVersion maps 1_21 to Java 21`() {
        assertEquals(21, svc.detectJavaVersion("1.21.1"))
        assertEquals(21, svc.detectJavaVersion("1.21.5"))
    }

    @Test
    fun `detectJavaVersion falls through unknown versions to Java 8 (legacy default)`() {
        // Anything we don't recognize (very old / future versions Aura
        // hasn't been updated for) defaults to Java 8 -- historically the
        // safest fallback because all SmartyCraft 1.x.x servers run on it.
        assertEquals(8, svc.detectJavaVersion("1.5.2"))
        assertEquals(8, svc.detectJavaVersion("alpha-1.0.0"))
    }

    // ── getOsName: os.name -> short tag ───────────────────────────────────

    @Test
    fun `getOsName maps Windows variants to win`() {
        withSystemProp("os.name", "Windows 10") {
            assertEquals("win", svc.getOsName())
        }
        withSystemProp("os.name", "Windows 11") {
            assertEquals("win", svc.getOsName())
        }
    }

    @Test
    fun `getOsName maps Linux to linux`() {
        withSystemProp("os.name", "Linux") {
            assertEquals("linux", svc.getOsName())
        }
    }

    @Test
    fun `getOsName maps macOS to mac`() {
        withSystemProp("os.name", "Mac OS X") {
            assertEquals("mac", svc.getOsName())
        }
    }

    @Test
    fun `getOsName maps unknown OSes to unknown`() {
        withSystemProp("os.name", "Plan9") {
            assertEquals("unknown", svc.getOsName())
        }
    }

    // ── getArchName: os.arch -> short tag ─────────────────────────────────

    @Test
    fun `getArchName maps aarch64 and arm64 to arm64`() {
        withSystemProp("os.arch", "aarch64") {
            assertEquals("arm64", svc.getArchName())
        }
        withSystemProp("os.arch", "arm64") {
            assertEquals("arm64", svc.getArchName())
        }
    }

    @Test
    fun `getArchName maps amd64 and x86_64 to x64`() {
        withSystemProp("os.arch", "amd64") {
            assertEquals("x64", svc.getArchName())
        }
        withSystemProp("os.arch", "x86_64") {
            assertEquals("x64", svc.getArchName())
        }
    }

    @Test
    fun `getArchName maps i386 and i686 to x32`() {
        withSystemProp("os.arch", "i386") {
            assertEquals("x32", svc.getArchName())
        }
        withSystemProp("os.arch", "i686") {
            assertEquals("x32", svc.getArchName())
        }
    }

    // ── getDownloadUrl: BellSoft URL matrix ──────────────────────────────

    @Test
    fun `getDownloadUrl returns BellSoft url for known os arch combos`() {
        // Spot-check one combo per Java major. Full matrix coverage isn't
        // needed -- the when() arms are repetitive and the value is
        // catching "url constant rotted" not "logic mistake".
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val url = svc.getDownloadUrl(8)
                assertNotNull(url)
                assertEquals(true, url.startsWith("https://download.bell-sw.com/java/"))
                assertEquals(true, url.contains("linux-amd64"))
                assertEquals(true, url.endsWith(".tar.gz"))
            }
        }
        withSystemProp("os.name", "Mac OS X") {
            withSystemProp("os.arch", "aarch64") {
                val url = svc.getDownloadUrl(21)
                assertNotNull(url)
                assertEquals(true, url.contains("macos-aarch64"))
            }
        }
        withSystemProp("os.name", "Windows 10") {
            withSystemProp("os.arch", "amd64") {
                val url = svc.getDownloadUrl(17)
                assertNotNull(url)
                assertEquals(true, url.contains("windows-amd64"))
                assertEquals(true, url.endsWith(".zip"))
            }
        }
    }

    @Test
    fun `getDownloadUrl returns null for unsupported os arch combos`() {
        // Linux/x32 not supported (BellSoft no longer ships).
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "i386") {
                assertNull(svc.getDownloadUrl(17))
                assertNull(svc.getDownloadUrl(21))
            }
        }
    }

    @Test
    fun `getDownloadUrl returns null for unknown Java major`() {
        // Anything outside {8, 17, 21} -- e.g., a hypothetical 25 we
        // haven't wired yet -- falls through to null. The download path
        // surfaces this as a clear "no Java build for this system" error.
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                assertNull(svc.getDownloadUrl(25))
                assertNull(svc.getDownloadUrl(11))
            }
        }
    }

    // ── findJavaExecutable: locate java/java.exe in BellSoft archive layout ─

    @Test
    fun `findJavaExecutable returns null for nonexistent directory`() {
        assertNull(svc.findJavaExecutable(workDir / "no-such-jdk"))
    }

    @Test
    fun `findJavaExecutable returns null when no java binary is present`() {
        val empty = (workDir / "empty-jdk").also { Files.createDirectories(it) }
        // Some other file present but no java/java.exe
        Files.writeString(empty / "README.md", "not a JDK")
        assertNull(svc.findJavaExecutable(empty))
    }

    @Test
    fun `findJavaExecutable locates java in nested bin directory (BellSoft layout)`() {
        // BellSoft tarballs put the binary at jdk-XX/bin/java
        val jdkRoot = (workDir / "fake-jdk").also { Files.createDirectories(it / "bin") }
        val javaBin = jdkRoot / "bin" / "java"
        Files.writeString(javaBin, "#!/bin/sh\necho 'fake'")
        javaBin.toFile().setExecutable(true)

        val found = svc.findJavaExecutable(jdkRoot)
        assertNotNull(found)
        assertEquals("java", found.fileName.toString())
    }

    // ── unzip: zip-slip protection (security-relevant) ───────────────────

    @Test
    fun `unzip rejects entries that escape the destination (zip-slip)`() {
        // Synthetic malicious zip: an entry with ../../../../etc/passwd
        // path. Without the normalize() check, this would write outside
        // dest. With it, the unzip throws.
        val malicious = workDir.resolve("evil.zip").toFile()
        ZipOutputStream(FileOutputStream(malicious)).use { zos ->
            zos.putNextEntry(ZipEntry("../../../../etc/aura-took-this.txt"))
            zos.write("escaped".toByteArray())
            zos.closeEntry()
        }

        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFails {
            svc.unzip(malicious, dest)
        }
    }

    @Test
    fun `unzip extracts well-formed archives normally`() {
        val benign = workDir.resolve("good.zip").toFile()
        ZipOutputStream(FileOutputStream(benign)).use { zos ->
            zos.putNextEntry(ZipEntry("subdir/inner.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }

        val dest = workDir / "good-target"
        Files.createDirectories(dest)
        svc.unzip(benign, dest)

        assertEquals("hello", Files.readString(dest / "subdir" / "inner.txt"))
    }

    @Test
    fun `unzip throws SecurityException on symlink entry (#187)`() {
        // BellSoft JDK ZIPs (Windows builds) ship plain files only. A symlink
        // entry would either be packaging accident or hostile redirect -- fail
        // hard so the JDK install surfaces loudly instead of corrupting state.
        val malicious = workDir.resolve("evil-jdk.zip").toFile()
        ZipArchiveOutputStream(FileOutputStream(malicious)).use { zos ->
            val link = ZipArchiveEntry("jdk/bin/javaw.exe")
            link.unixMode = UnixStat.LINK_FLAG or 0b111_111_111
            zos.putArchiveEntry(link)
            zos.write("../../../../etc/passwd".toByteArray())
            zos.closeArchiveEntry()
        }
        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFailsWith<SecurityException> { svc.unzip(malicious, dest) }
    }

    // ── untargz: zip-slip + symlink/special-type rejection ──────────────

    @Test
    fun `untargz throws on tar entries that escape the destination`() {
        val malicious = workDir.resolve("evil.tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(malicious))).use { tos ->
            val entry = TarArchiveEntry("../../etc/aura-took-this.txt")
            entry.size = "escaped".length.toLong()
            tos.putArchiveEntry(entry)
            tos.write("escaped".toByteArray())
            tos.closeArchiveEntry()
        }
        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFails { svc.untargz(malicious, dest) }
    }

    @Test
    fun `untargz throws SecurityException on symbolic-link tar entry (#187)`() {
        val malicious = workDir.resolve("evil-link.tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(malicious))).use { tos ->
            val link = TarArchiveEntry("inside-link", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "/etc/passwd"
            tos.putArchiveEntry(link)
            tos.closeArchiveEntry()
        }
        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFailsWith<SecurityException> {
            svc.untargz(malicious, dest)
        }
    }

    @Test
    fun `untargz throws SecurityException on hard-link tar entry (#187)`() {
        val malicious = workDir.resolve("evil-hardlink.tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(malicious))).use { tos ->
            val link = TarArchiveEntry("inside-hard", TarArchiveEntry.LF_LINK)
            link.linkName = "../../../../etc/shadow"
            tos.putArchiveEntry(link)
            tos.closeArchiveEntry()
        }
        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFailsWith<SecurityException> {
            svc.untargz(malicious, dest)
        }
    }

    // ── #202: in-target symlinks must be ALLOWED ─────────────────────────
    //
    // BellSoft's Linux/macOS JDK tarballs ship legitimate intra-package
    // symlinks (jre/lib/.../libjsig.so -> libjsig.so.0 and similar). The
    // post-#187 blanket rejection turned every Linux fresh installation into a
    // SecurityException at JDK-extract time. Allow them when the target,
    // resolved relative to the symlink's parent, stays within [dest].

    @Test
    fun `untargz allows in-target symbolic link (BellSoft JDK layout)`() {
        val archive = workDir.resolve("jdk-with-symlink.tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(archive))).use { tos ->
            // The real target file.
            val real = TarArchiveEntry("lib/libjsig.so.0")
            val bytes = "fake-elf".toByteArray()
            real.size = bytes.size.toLong()
            tos.putArchiveEntry(real)
            tos.write(bytes)
            tos.closeArchiveEntry()
            // Symlink alongside it pointing to the real file via a relative path
            // -- same shape as the BellSoft tarball entry that previously broke
            // Linux fresh installations.
            val link = TarArchiveEntry("lib/libjsig.so", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "libjsig.so.0"
            tos.putArchiveEntry(link)
            tos.closeArchiveEntry()
        }
        val dest = workDir / "extract-jdk"
        Files.createDirectories(dest)

        svc.untargz(archive, dest)

        val symlink = dest / "lib" / "libjsig.so"
        assertEquals(true, Files.isSymbolicLink(symlink), "expected symlink at $symlink")
        // Resolving the link should land on the real file inside dest.
        assertEquals("fake-elf", Files.readString(symlink.toRealPath()))
    }

    @Test
    fun `untargz rejects symbolic link whose target escapes destination`() {
        val malicious = workDir.resolve("evil-escaping-symlink.tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(malicious))).use { tos ->
            // Relative path that climbs out of [dest] entirely.
            val link = TarArchiveEntry("inside/safe-name", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "../../../../etc/passwd"
            tos.putArchiveEntry(link)
            tos.closeArchiveEntry()
        }
        val dest = workDir / "extract-target"
        Files.createDirectories(dest)
        assertFailsWith<SecurityException> {
            svc.untargz(malicious, dest)
        }
    }

    @Test
    fun `unzip allows in-target symbolic link`() {
        val archive = workDir.resolve("zip-with-symlink.zip").toFile()
        ZipArchiveOutputStream(FileOutputStream(archive)).use { zos ->
            // Real file.
            val real = ZipArchiveEntry("inner/target.txt")
            val bytes = "hello".toByteArray()
            real.size = bytes.size.toLong()
            zos.putArchiveEntry(real)
            zos.write(bytes)
            zos.closeArchiveEntry()
            // Symlink with target stored as the entry's payload (Zip convention).
            val link = ZipArchiveEntry("inner/link.txt")
            link.unixMode = UnixStat.LINK_FLAG or 0b111_111_111
            zos.putArchiveEntry(link)
            zos.write("target.txt".toByteArray())
            zos.closeArchiveEntry()
        }
        val dest = workDir / "extract-zip-target"
        Files.createDirectories(dest)

        svc.unzip(archive, dest)

        val symlink = dest / "inner" / "link.txt"
        assertEquals(true, Files.isSymbolicLink(symlink), "expected symlink at $symlink")
        assertEquals("hello", Files.readString(symlink.toRealPath()))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private inline fun <T> withSystemProp(key: String, value: String, block: () -> T): T {
        val original = System.getProperty(key)
        try {
            System.setProperty(key, value)
            return block()
        } finally {
            if (original == null) System.clearProperty(key)
            else System.setProperty(key, original)
        }
    }
}
