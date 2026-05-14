package hivens.launcher

import hivens.core.data.FileData
import hivens.core.data.FileManifest
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
 * The big network-driven flow ([processSession]) gets covered by the
 * existing [LaunchPipelineIntegrationTest] (MockEngine + tmpdir).
 * Here we focus on the helper logic that can produce subtle bugs:
 * path normalization (server might or might not include the assetDir
 * prefix), manifest flattening (recursive directories), MD5 against
 * known content, and the file-staleness predicate (which gates ALL
 * downloads — wrong logic = mass re-download or missed updates).
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
        svc = FileDownloadService(
            buildMockClient(""),
            protectedPaths,
            manifestCache,
            hivens.launcher.network.ServerProtocolConfig(),
        )
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── normalizePath: strip server-prefix, keep canonical mod/config dirs ──

    @Test
    fun `normalizePath strips assetDir prefix in front of a recognized root`() {
        // "Industrial/mods/foo.jar" → "mods/foo.jar"
        assertEquals("mods/foo.jar", svc.normalizePath("Industrial/mods/foo.jar"))
        assertEquals("config/options.txt", svc.normalizePath("SkyBlock/config/options.txt"))
    }

    @Test
    fun `normalizePath leaves canonical-root paths intact`() {
        // First segment is already a recognized root → no stripping.
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
        // First segment starts with "mods" — counts as canonical even if
        // it's "modsBackup" — by design (per the startsWith check in code).
        // This pins current behavior; if intent ever changes (exact-match
        // only), update both impl and this test together.
        assertEquals("modsArchive/old.jar", svc.normalizePath("modsArchive/old.jar"))
    }

    // ── flattenManifest: nested directories → flat (path, FileData) map ──

    @Test
    fun `flattenManifest empty manifest yields empty map`() {
        val flat = svc.flattenManifest(FileManifest())
        assertTrue(flat.isEmpty())
    }

    @Test
    fun `flattenManifest puts root-level files at their bare key`() {
        val manifest = FileManifest(
            files = mapOf("extra.zip" to FileData(md5 = "abc", size = 100L)),
        )
        val flat = svc.flattenManifest(manifest)
        assertEquals(1, flat.size)
        assertTrue(flat.containsKey("extra.zip"))
    }

    @Test
    fun `flattenManifest joins directory keys with slashes`() {
        val manifest = FileManifest(
            directories = mapOf(
                "mods" to FileManifest(
                    files = mapOf(
                        "industrialcraft.jar" to FileData(md5 = "111", size = 1000L),
                        "buildcraft.jar"      to FileData(md5 = "222", size = 2000L),
                    ),
                ),
            ),
        )
        val flat = svc.flattenManifest(manifest)
        assertEquals(2, flat.size)
        assertTrue(flat.containsKey("mods/industrialcraft.jar"))
        assertTrue(flat.containsKey("mods/buildcraft.jar"))
    }

    @Test
    fun `flattenManifest recurses into deeply nested directory trees`() {
        val manifest = FileManifest(
            directories = mapOf(
                "config" to FileManifest(
                    directories = mapOf(
                        "industrialcraft" to FileManifest(
                            files = mapOf("recipes.cfg" to FileData(md5 = "x", size = 10L)),
                        ),
                    ),
                ),
            ),
        )
        val flat = svc.flattenManifest(manifest)
        assertEquals(1, flat.size)
        assertTrue(flat.containsKey("config/industrialcraft/recipes.cfg"))
    }

    // ── calculateMD5: known content → known hash ─────────────────────────

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
    fun `isFileMissingOrChanged respects ProtectedPaths — present-and-non-empty wins over hash mismatch`() {
        // ProtectedPaths protects user-edited config files — once the
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
}
