package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.test.buildMockClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.condition.EnabledOnOs
import hivens.core.platform.OS as PlatformOS
import hivens.test.testTransferEngine
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaManagerServiceTest {

    private lateinit var workDir: Path
    private lateinit var svc: JavaManagerService

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-jm-test-")
        svc = JavaManagerService(workDir, testTransferEngine(buildMockClient("")))
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
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

    // os.name/os.arch -> token mapping now lives on Platform/Arch; see
    // client-core OSTest. The URL-matrix tests below exercise the live
    // os.name/os.arch path end-to-end through OS.

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
        // Anything outside the wired-in matrix {8, 17, 21, 25} -- e.g. Java 11,
        // which nothing in our target MC/loader graph needs -- falls through to
        // null. The download path surfaces this as a clear "no Java build for
        // this system" error.
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                assertNull(svc.getDownloadUrl(11))
            }
        }
    }

    // ── getAdoptiumUrl + getDownloadUrls: fallback mirror plumbing ──────────

    @Test
    fun `getAdoptiumUrl shapes GitHub-release path with +-encoded build tag`() {
        // Filed 2026-05-24: RF tester on CloudFlare WARP got blanket
        // 403 from BellSoft (CloudFlare bot manager hostile to its own
        // WARP exits). Adoptium on GitHub releases dodges both legs of
        // the CloudFlare double-whammy. This test pins the URL shape so
        // a future Temurin tag rotation surfaces in tests, not in a
        // user report.
        withSystemProp("os.name", "Windows 10") {
            withSystemProp("os.arch", "amd64") {
                val url21 = svc.getAdoptiumUrl(21)!!
                assertEquals(true, url21.startsWith("https://github.com/adoptium/temurin21-binaries/releases/download/"))
                assertEquals(true, url21.contains("%2B"))                // + is %2B-encoded
                assertEquals(true, url21.endsWith("_windows_hotspot_21.0.5_11.zip"))

                val url17 = svc.getAdoptiumUrl(17)!!
                assertEquals(true, url17.contains("temurin17-binaries"))
                assertEquals(true, url17.endsWith("_windows_hotspot_17.0.13_11.zip"))

                val url8 = svc.getAdoptiumUrl(8)!!
                // Java 8 tag prefix is `jdk` (no dash), 9+ is `jdk-`.
                assertEquals(true, url8.contains("/download/jdk8u442-b06/"))
                assertEquals(true, url8.endsWith("_windows_hotspot_8u442b06.zip"))
            }
        }
    }

    @Test
    fun `getDownloadUrls returns BellSoft first then Adoptium`() {
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val urls = svc.getDownloadUrls(21)
                assertEquals(2, urls.size)
                assertEquals(true, urls[0].startsWith("https://download.bell-sw.com/"))
                assertEquals(true, urls[1].startsWith("https://github.com/adoptium/"))
            }
        }
    }

    @Test
    fun `getDownloadUrls is empty for unsupported os arch combos`() {
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "i386") {
                // Neither mirror ships Linux x86, so the list collapses
                // to empty rather than half-populated -- caller throws
                // "no Java build for this system" instead of trying a
                // mismatched arch.
                assertEquals(emptyList<String>(), svc.getDownloadUrls(21))
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

    // ── isJavaUsable: subprocess gate against broken JDK extractions ──────

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `isJavaUsable returns true for runnable binary exiting 0`() {
        val ok = workDir / "fakejava"
        Files.writeString(ok, "#!/bin/sh\nexit 0\n")
        ok.toFile().setExecutable(true)
        assertTrue(svc.isJavaUsable(ok))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `isJavaUsable returns false for executable file with no valid interpreter`() {
        // +x bit set but content is not a valid script or ELF -- the
        // exec syscall fails and the spawned process never starts.
        val broken = workDir / "garbage"
        Files.writeString(broken, "this is not a script or executable")
        broken.toFile().setExecutable(true)
        assertFalse(svc.isJavaUsable(broken))
    }

    @Test
    fun `isJavaUsable returns false for missing file`() {
        assertFalse(svc.isJavaUsable(workDir / "does-not-exist"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `isJavaUsable returns false when subprocess does not finish within timeout`() {
        // A real broken JDK can hang during the dynamic linker stage. The
        // 5s timeout fires and destroyForcibly cleans up; we must not
        // wait forever.
        val slow = workDir / "slow"
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n")
        slow.toFile().setExecutable(true)
        assertFalse(svc.isJavaUsable(slow))
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

    // ── getJavaPath: orchestrator (find -> download -> find -> +x) ────────

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getJavaPath returns existing executable without triggering download`() {
        // Pre-place a valid-looking java binary in the version-specific
        // runtime dir. The dead-HttpClient provider catches a regression
        // where the early-return gate fails and the orchestrator falls
        // through to downloadAndUnpack.
        //
        // Linux/Mac only: even though the test simulates Linux via
        // os.name/os.arch overrides, the underlying file system probe
        // (Files.isExecutable on the pre-placed `bin/java` binary)
        // runs on the real OS. On Windows NTFS, isExecutable returns
        // false for extensionless files regardless of setExecutable(true),
        // so the orchestrator decides "binary not present" and falls
        // through to download -- hits the dead HttpClient and throws.
        // Same rationale as the tar.gz unpack test below.
        val runtimesRoot = workDir
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val folderName = "java-21-linux-x64"  // matches getJavaPath naming
                val targetDir = runtimesRoot / "runtimes" / folderName
                val binDir = (targetDir / "bin").also { Files.createDirectories(it) }
                Files.writeString(binDir / "java", "#!/bin/sh\necho fake")
                binDir.resolve("java").toFile().setExecutable(true)

                val resolved = runBlocking {
                    JavaManagerService(runtimesRoot, testTransferEngine(deadHttpClientProvider()))
                        .getJavaPath("1.21.1")
                }
                assertEquals("java", resolved.fileName.toString())
                assertTrue(Files.exists(resolved))
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getJavaPath downloads tar-gz and locates java executable when missing`() {
        // Synthesise a JDK tarball with the canonical jdk-XX/bin/java
        // layout. The tar entry's mode (0o755) marks the binary executable,
        // and untargz's per-entry mode check sets +x post-extraction so
        // findJavaExecutable's Files.isExecutable gate passes.
        //
        // Linux/Mac only: POSIX-permissions plumbing doesn't apply on
        // Windows. The orchestrator is exercised on the other paths via
        // [getJavaPath returns existing executable...] above.
        val runtimesRoot = workDir
        val tarGz = synthesiseJdkTarGz("jdk-21.0.9+15/bin/java")
        val provider = mockClientWithBytes(tarGz)

        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val resolved = runBlocking {
                    JavaManagerService(runtimesRoot, testTransferEngine(provider)).getJavaPath("1.21.1")
                }
                assertEquals("java", resolved.fileName.toString())
                assertTrue(Files.isExecutable(resolved),
                    "post-download +x must be applied on non-Windows so isExecutable passes")
            }
        }
    }

    @Test
    fun `getJavaPath throws when no BellSoft URL exists for the os arch combo`() {
        // Linux/i386 + Java 21 is not in the BellSoft URL matrix --
        // getDownloadUrl returns null and downloadAndUnpack throws
        // IOException("no Java build for this system..."). Test pins os.arch
        // to a value Arch.classify maps to "x32" so the lookup misses.
        val runtimesRoot = workDir
        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "i386") {
                val ex = assertFails {
                    runBlocking {
                        JavaManagerService(runtimesRoot, testTransferEngine(buildMockClient("")))
                            .getJavaPath("1.21.1")
                    }
                }
                assertTrue(
                    ex is IOException &&
                        ex.message?.contains("no Java build", ignoreCase = true) == true,
                    "expected an IOException about missing Java build, got ${ex::class.simpleName}: ${ex.message}",
                )
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getJavaPath throws when downloaded archive lacks a java executable`() {
        // Server returns a syntactically valid tarball that has zero
        // recognisable java binaries. downloadAndUnpack succeeds, the
        // second findJavaExecutable returns null, and getJavaPath throws
        // a clear IOException so the calling pipeline surfaces a meaningful
        // error instead of dying later with a missing-binary message.
        val runtimesRoot = workDir
        val tarGz = synthesiseJdkTarGz("garbage/bin/not-java")
        val provider = mockClientWithBytes(tarGz)

        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val ex = assertFails {
                    runBlocking {
                        JavaManagerService(runtimesRoot, testTransferEngine(provider)).getJavaPath("1.21.1")
                    }
                }
                assertTrue(
                    ex is IOException &&
                        ex.message?.contains("executable file was not found", ignoreCase = true) == true,
                    "expected an IOException about missing java binary, got ${ex::class.simpleName}: ${ex.message}",
                )
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getJavaPath re-downloads when existing executable fails -version check`() {
        // Pre-place a "java" with the +x bit but content that cannot
        // execute (no shebang, no interpreter). isJavaUsable rejects it,
        // the orchestrator must fall through to download instead of
        // returning a path that would die with exit 127 when launched.
        val runtimesRoot = workDir
        val folderName = "java-21-linux-x64"
        val targetDir = runtimesRoot / "runtimes" / folderName
        val binDir = (targetDir / "bin").also { Files.createDirectories(it) }
        Files.writeString(binDir / "java", "broken bytes with no shebang")
        binDir.resolve("java").toFile().setExecutable(true)

        val tarGz = synthesiseJdkTarGz("jdk-21.0.9+15/bin/java")
        val provider = mockClientWithBytes(tarGz)

        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val resolved = runBlocking {
                    JavaManagerService(runtimesRoot, testTransferEngine(provider)).getJavaPath("1.21.1")
                }
                assertEquals("java", resolved.fileName.toString())
                assertTrue(
                    svc.isJavaUsable(resolved),
                    "re-downloaded Java must be runnable so the next call short-circuits",
                )
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getJavaPath throws when freshly downloaded java fails -version check`() {
        // Server returns a tarball whose "java" exists at the expected
        // path with the +x bit but cannot run. This is the libjli-missing
        // shape: findJavaExecutable returns a path, isJavaUsable rejects
        // it, the orchestrator throws so the caller surfaces a clear
        // error instead of handing back a path that will exit 127.
        val runtimesRoot = workDir
        val tarGz = synthesiseJdkTarGz(
            "jdk-21.0.9+15/bin/java",
            "unrunnable garbage with no shebang".toByteArray(),
        )
        val provider = mockClientWithBytes(tarGz)

        withSystemProp("os.name", "Linux") {
            withSystemProp("os.arch", "amd64") {
                val ex = assertFails {
                    runBlocking {
                        JavaManagerService(runtimesRoot, testTransferEngine(provider)).getJavaPath("1.21.1")
                    }
                }
                assertTrue(
                    ex is IOException &&
                        ex.message?.contains("failed -version", ignoreCase = true) == true,
                    "expected IOException about failed -version check, got ${ex::class.simpleName}: ${ex.message}",
                )
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Build a minimal in-memory `.tar.gz` containing a single regular-file
     * entry at [path] with execute-bit-set mode. Default [payload] is a
     * minimal shell script that exits 0 -- runnable enough for the
     * isJavaUsable `-version` probe to return true after extraction.
     * Override [payload] for tests that need an explicitly unrunnable
     * binary (e.g. the libjli-missing shape).
     */
    private fun synthesiseJdkTarGz(
        path: String,
        payload: ByteArray = "#!/bin/sh\nexit 0\n".toByteArray(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val entry = TarArchiveEntry(path)
                entry.mode = 0b111_101_101  // 0o755 = rwxr-xr-x
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * MockEngine-backed provider that returns the same [bytes] for any
     * URL. JavaManagerService only ever issues one GET per getJavaPath
     * call, so URL matching isn't necessary -- the test just needs the
     * download step to consume well-formed archive bytes.
     */
    private fun mockClientWithBytes(bytes: ByteArray): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(bytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                    )
                }
            }
        }
        return HttpClientProvider { client }
    }

    /**
     * Provider whose HttpClient errors on any request. Used in tests that
     * expect getJavaPath to short-circuit (existing executable on disk) so
     * a regression where the early-return fails throws loudly instead of
     * silently exercising the download path against a real BellSoft URL.
     */
    private fun deadHttpClientProvider(): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    error("unexpected HTTP call -- getJavaPath should have short-circuited on the pre-existing binary")
                }
            }
        }
        return HttpClientProvider { client }
    }

    // ── original helpers ──────────────────────────────────────────────────

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

    // ── installUnpacked: the previous JVM survives a bad archive ──────────

    /**
     * A tar.gz rather than a zip: the executable bit rides in the tar entry
     * mode, and findJavaExecutable only counts a `java` that carries it -- the
     * same predicate production uses on a real BellSoft tarball.
     */
    private fun tarGzOnDisk(name: String, entries: Map<String, String>): Path {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                entries.forEach { (path, content) ->
                    val payload = content.toByteArray()
                    val entry = TarArchiveEntry(path)
                    entry.mode = 0b111_101_101
                    entry.size = payload.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(payload)
                    tar.closeArchiveEntry()
                }
            }
        }
        return Files.write(workDir.resolve(name), out.toByteArray())
    }

    private fun installedJava(dir: Path): Path {
        val exe = dir.resolve("bin/java")
        Files.createDirectories(exe.parent)
        Files.writeString(exe, "#!/bin/sh\n")
        exe.toFile().setExecutable(true)
        return exe
    }

    @Test
    fun `a good archive replaces the installed runtime`() {
        val target = workDir.resolve("java-21-linux-amd64")
        installedJava(target)
        Files.writeString(target.resolve("marker.txt"), "OLD")

        val archive = tarGzOnDisk("good.tar.gz", mapOf("jdk/bin/java" to "#!/bin/sh\n", "jdk/marker.txt" to "NEW"))
        svc.installUnpacked(archive, target, isZip = false)

        assertEquals("NEW", Files.readString(target.resolve("jdk/marker.txt")))
        assertFalse(Files.exists(target.resolve("marker.txt")), "the old tree must be replaced, not merged")
    }

    @Test
    fun `an archive with no java executable leaves the installed runtime alone`() {
        // What a truncated or wrong-arch download looks like on disk. The old
        // order emptied the target first, so the working JVM was already gone
        // by the time the unpack turned out to be useless -- and the retry loop
        // moved to the next mirror with no fallback left.
        val target = workDir.resolve("java-21-linux-amd64")
        val exe = installedJava(target)

        val archive = tarGzOnDisk("truncated.tar.gz", mapOf("jdk/README" to "not a jvm"))
        assertFailsWith<IOException> { svc.installUnpacked(archive, target, isZip = false) }

        assertTrue(Files.exists(exe), "the working runtime was destroyed by a bad download")
    }

    @Test
    fun `a failed install leaves no staging directories behind`() {
        val target = workDir.resolve("java-21-linux-amd64")
        installedJava(target)

        val archive = tarGzOnDisk("truncated2.tar.gz", mapOf("jdk/README" to "not a jvm"))
        runCatching { svc.installUnpacked(archive, target, isZip = false) }

        assertFalse(Files.exists(workDir.resolve("java-21-linux-amd64.incoming")))
        assertFalse(Files.exists(workDir.resolve("java-21-linux-amd64.previous")))
    }

    @Test
    fun `a bad download through the real path leaves the previous runtime installed`() {
        // The three cases above drive installUnpacked directly, which pins its
        // contract but not that the download path still uses it. This one goes
        // through getJavaPath, so it fails if that wiring is ever undone.
        // Under runtimes/, which is where the service derives its target from
        // baseDir -- putting the stub anywhere else makes the whole test pass
        // for the wrong reason, because nothing ever touches that path.
        val target = workDir.resolve("runtimes")
            .resolve("java-21-${PlatformOS.platform.bellsoft}-${PlatformOS.arch.bellsoft}")
        val exe = target.resolve("bin/java")
        Files.createDirectories(exe.parent)
        // Present and executable so it is FOUND, but not a real program, so the
        // -version probe fails and the service proceeds to re-download. A stub
        // that exits cleanly would short-circuit before any download happens.
        Files.writeString(exe, "definitely not an ELF header")
        exe.toFile().setExecutable(true)

        val garbage = synthesiseJdkTarGz("garbage/bin/not-java")
        val svc = JavaManagerService(workDir, testTransferEngine(mockClientWithBytes(garbage)))

        assertFails { runBlocking { svc.getJavaPathForMajor(21) } }

        assertTrue(Files.exists(exe), "a failed re-download destroyed the runtime it was replacing")
        assertEquals("definitely not an ELF header", Files.readString(exe))
    }
}
