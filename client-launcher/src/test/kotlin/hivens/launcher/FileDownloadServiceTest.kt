package hivens.launcher

import hivens.test.testTransferEngine
import hivens.test.buildMockClient
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the pure-function helpers inside FileDownloadService.
 *
 * The big network-driven flow (`processSession`) gets covered by the
 * existing [LaunchPipelineIntegrationTest] (MockEngine + tmpdir).
 * Here we focus on the helper logic that can produce subtle bugs:
 * path normalization (server might or might not include the assetDir
 * prefix), manifest flattening (recursive directories), MD5 against
 * known content, and the file-staleness predicate (which gates ALL
 * downloads -- wrong logic = mass re-download or missed updates).
 */
class FileDownloadServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var workDir: Path
    private lateinit var svc: FileDownloadService

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-fds-test-")
        val protectedPaths = ProtectedPaths(workDir / "protected-paths.json", json)
        val manifestCache  = ManifestCache(workDir / "manifest-cache", json)
        val provider = buildMockClient("")
        svc = FileDownloadService(
            testTransferEngine(provider),
            protectedPaths,
            manifestCache,
            hivens.launcher.network.ServerProtocolConfig(),
        )
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    // ── normalizePath: strip server-prefix, keep canonical mod/config dirs ──

    @Test
    fun `normalizePath strips assetDir prefix in front of a recognized root`() {
        // "Industrial/mods/foo.jar" -> "mods/foo.jar"
        assertEquals("mods/foo.jar", svc.normalizePath("Industrial/mods/foo.jar"))
        assertEquals("config/options.txt", svc.normalizePath("SkyBlock/config/options.txt"))
    }

    @Test
    fun `normalizePath leaves canonical-root paths intact`() {
        // First segment is already a recognized root -> no stripping.
        assertEquals("mods/foo.jar", svc.normalizePath("mods/foo.jar"))
        assertEquals("libraries/asm-5.0.3.jar", svc.normalizePath("libraries/asm-5.0.3.jar"))
        assertEquals("natives/lwjgl.so", svc.normalizePath("natives/lwjgl.so"))
    }

    @Test
    fun `normalizePath returns single-segment paths unchanged`() {
        // Edge: just a filename, no slashes.
        assertEquals("extra.zip", svc.normalizePath("extra.zip"))
    }

    @Test
    fun `normalizePath handles partial root prefix matches`() {
        // First segment starts with "mods" -- counts as canonical even if
        // it's "modsBackup" -- by design (per the startsWith check in code).
        // This pins current behavior; if intent ever changes (exact-match
        // only), update both impl and this test together.
        assertEquals("modsArchive/old.jar", svc.normalizePath("modsArchive/old.jar"))
    }

    // Manifest flattening now lives on FileManifest.flatten(); see
    // client-core FileManifestFlattenTest.

    // ── calculateMD5: known content -> known hash ─────────────────────────

    @Test
    fun `calculateMD5 returns known hash for empty file`() {
        val f = workDir / "empty.bin"
        Files.createFile(f)
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e (RFC 1321 test vector)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", svc.calculateMD5(f))
    }

    @Test
    fun `calculateMD5 returns known hash for the abc test vector`() {
        val f = workDir / "abc.bin"
        Files.writeString(f, "abc")
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72 (RFC 1321 test vector)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", svc.calculateMD5(f))
    }

    @Test
    fun `calculateMD5 produces stable lowercase hex regardless of input bytes`() {
        val f = workDir / "binary.bin"
        Files.write(f, byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))
        val hash = svc.calculateMD5(f)
        // 32 lowercase hex chars
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── isFileMissingOrChanged: download-gating predicate ────────────────

    @Test
    fun `isFileMissingOrChanged returns true when file does not exist`() {
        assertTrue(svc.isFileMissingOrChanged(workDir / "ghost.jar", "abc", "mods/ghost.jar"))
    }

    @Test
    fun `isFileMissingOrChanged returns true when file is empty (caught by size check)`() {
        val empty = workDir / "empty.jar"
        Files.createFile(empty)
        assertTrue(svc.isFileMissingOrChanged(empty, "abc", "mods/empty.jar"))
    }

    @Test
    fun `isFileMissingOrChanged returns false when expected hash is the special any sentinel`() {
        // "any" is the wire convention from the upstream manifest: file
        // exists, don't validate. Used for files where the server doesn't
        // care about content matching (logs, caches, etc.).
        val f = workDir / "any.txt"
        Files.writeString(f, "anything goes here")
        assertFalse(svc.isFileMissingOrChanged(f, "any", "mods/any.txt"))
    }

    @Test
    fun `isFileMissingOrChanged returns false when MD5 matches`() {
        val f = workDir / "matches.txt"
        Files.writeString(f, "abc")
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertFalse(svc.isFileMissingOrChanged(f, "900150983cd24fb0d6963f7d28e17f72", "mods/matches.txt"))
    }

    @Test
    fun `isFileMissingOrChanged is case-insensitive on the hex hash`() {
        val f = workDir / "case.txt"
        Files.writeString(f, "abc")
        // Same hash uppercase
        assertFalse(svc.isFileMissingOrChanged(f, "900150983CD24FB0D6963F7D28E17F72", "mods/case.txt"))
    }

    @Test
    fun `isFileMissingOrChanged returns true when MD5 does not match`() {
        val f = workDir / "stale.txt"
        Files.writeString(f, "old content")
        assertTrue(svc.isFileMissingOrChanged(f, "deadbeef00000000deadbeef00000000", "mods/stale.txt"))
    }

    @Test
    fun `isFileMissingOrChanged respects ProtectedPaths -- present-and-non-empty wins over hash mismatch`() {
        // ProtectedPaths protects user-edited config files -- once the
        // file exists with content, we do NOT overwrite it on sync,
        // even if the upstream hash differs. Default protected list
        // includes options.txt, servers.dat, etc.
        val f = workDir / "options.txt"
        Files.writeString(f, "user-customized content")
        // Upstream wants a different hash; default protected list shields it.
        assertFalse(
            svc.isFileMissingOrChanged(f, "deadbeef00000000deadbeef00000000", "options.txt"),
            "protected paths must short-circuit before MD5 comparison",
        )
    }

    // ── ZIP-structure scan for mods/*.jar ───────────────────────────────────

    @Test
    fun `isFileMissingOrChanged returns true for corrupt mods jar even when MD5 matches`() {
        // Bug reproducer: bytes-on-disk match the manifest's MD5 verbatim
        // (server CDN serves them, hash matches), but the bytes don't form
        // a valid ZIP -- NeoForge BootstrapLauncher dies with
        // `invalid CEN header (bad signature)` at launch. Pre-fix the
        // launcher said "all good" and let the user crash; post-fix it
        // forces a redownload.
        val jar = workDir / "mods" / "broken.jar"
        Files.createDirectories(jar.parent)
        val garbage = "this is not a zip archive at all".toByteArray()
        Files.write(jar, garbage)
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(garbage)
            .joinToString("") { "%02x".format(it) }
        // The MD5 matches what's on disk -- only the ZIP-validity check
        // can detect this. Without the fix, isFileMissingOrChanged would
        // return false (file fine) -> game launches -> crash.
        assertTrue(
            svc.isFileMissingOrChanged(jar, md5, "mods/broken.jar"),
            "matching MD5 must NOT shield a corrupt mods jar from re-download",
        )
    }

    @Test
    fun `isFileMissingOrChanged accepts valid mods jar with matching MD5`() {
        val jar = workDir / "mods" / "ok.jar"
        Files.createDirectories(jar.parent)
        // Build a minimal but well-formed JAR.
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\n".toByteArray())
            jos.closeEntry()
        }
        val bytes = Files.readAllBytes(jar)
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertFalse(svc.isFileMissingOrChanged(jar, md5, "mods/ok.jar"))
    }

    @Test
    fun `ZIP-validity scan is scoped to mods only (libraries jars skip the scan)`() {
        // Performance scope (#169 cheaper-alternative): scanning every
        // libraries-dir JAR would dominate cold-start on heavy modpacks.
        // Corruption is heavily concentrated in mods/, so only that
        // subtree pays the JarFile open cost. Verify a corrupt jar
        // OUTSIDE mods/ with matching MD5 is still considered fine --
        // we accept the residual risk to keep cold start snappy.
        val jar = workDir / "libraries" / "broken.jar"
        Files.createDirectories(jar.parent)
        val garbage = "not a zip".toByteArray()
        Files.write(jar, garbage)
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(garbage)
            .joinToString("") { "%02x".format(it) }
        assertFalse(
            svc.isFileMissingOrChanged(jar, md5, "libraries/broken.jar"),
            "libraries jars are intentionally not scanned -- keeps cold start fast",
        )
    }

    @Test
    fun `ZIP-validity scan triggers in nested mods subdirectories`() {
        // Modpack convention: mods/<version>/foo.jar (e.g. mods/1.21.1/X.jar).
        // The scope predicate must accept these too.
        val jar = workDir / "mods" / "1.21.1" / "broken.jar"
        Files.createDirectories(jar.parent)
        val garbage = "not a zip".toByteArray()
        Files.write(jar, garbage)
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(garbage)
            .joinToString("") { "%02x".format(it) }
        assertTrue(svc.isFileMissingOrChanged(jar, md5, "mods/1.21.1/broken.jar"))
    }
}
