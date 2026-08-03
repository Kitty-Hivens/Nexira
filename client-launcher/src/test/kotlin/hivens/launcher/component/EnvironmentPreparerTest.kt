package hivens.launcher.component

import hivens.test.testTransferEngine
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
import java.io.ByteArrayOutputStream
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
import kotlin.test.assertTrue

class EnvironmentPreparerTest {

    private lateinit var workDir: Path
    private lateinit var svc: EnvironmentPreparer

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-envprep-test-")
        svc = EnvironmentPreparer(testTransferEngine(buildMockClient("")))
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // os.name -> LWJGL classifier suffix now lives on Platform.lwjgl; see
    // client-core OSTest.

    // ── natives are derived material, not user data ──────────────────────────

    private fun hostNativeName(): String = when (hivens.core.platform.OS.platform.lwjgl) {
        "windows" -> "lwjgl.dll"
        "macos" -> "lwjgl.dylib"
        else -> "lwjgl.so"
    }

    /** A jar carrying one native under the host platform's own name. */
    private fun nativeJar(name: String, bytes: ByteArray): Path {
        val jar = workDir / name
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry(hostNativeName()))
            zip.write(bytes)
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `an unbound launch keeps what it finds in the natives folder`() = runBlocking {
        val jar = nativeJar("lwjgl-natives.jar", "genuine".toByteArray())
        val nativesDir = (workDir / "bin/natives").also { Files.createDirectories(it) }
        Files.write(nativesDir / hostNativeName(), "tampered".toByteArray())

        svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(jar), rebuild = false)

        assertEquals("tampered", Files.readString(nativesDir / hostNativeName()))
    }

    /**
     * The folder is what `java.library.path` points at, so whatever sits under
     * these names is what the JVM loads into the game process. A launch that
     * will carry a token re-derives it from the jars instead.
     */
    @Test
    fun `a bound launch re-derives the natives it was given`() = runBlocking {
        val jar = nativeJar("lwjgl-natives.jar", "genuine".toByteArray())
        val nativesDir = (workDir / "bin/natives").also { Files.createDirectories(it) }
        Files.write(nativesDir / hostNativeName(), "tampered".toByteArray())

        svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(jar), rebuild = true)

        assertEquals("genuine", Files.readString(nativesDir / hostNativeName()))
    }

    /**
     * Wiping with no complete source to rebuild from would cost the instance its
     * natives for a reason its owner cannot act on, so the incomplete case keeps
     * what is on disk rather than emptying the folder.
     */
    @Test
    fun `a rebuild with a missing source jar does not empty the folder`() = runBlocking {
        val nativesDir = (workDir / "bin/natives").also { Files.createDirectories(it) }
        Files.write(nativesDir / hostNativeName(), "on-disk".toByteArray())

        svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(workDir / "absent.jar"), rebuild = true)

        assertTrue(Files.exists(nativesDir / hostNativeName()))
        assertEquals("on-disk", Files.readString(nativesDir / hostNativeName()))
    }

    // ── isFolderValidForOs: per-platform native-extension presence check ──

    @Test
    fun `isFolderValidForOs returns false for nonexistent directory`() {
        assertFalse(svc.isFolderValidForOs(workDir / "no-such", "linux"))
    }

    @Test
    fun `isFolderValidForOs returns true when matching extension is present`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "lwjgl.so")
        assertTrue(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    @Test
    fun `isFolderValidForOs returns false when only wrong-platform natives are present`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "lwjgl.dll")
        Files.createFile(nativesDir / "lwjgl.dylib")
        // Asking for linux -- only .so counts.
        assertFalse(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    @Test
    fun `isFolderValidForOs honours per-platform extension`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "lwjgl.dll")
        assertTrue(svc.isFolderValidForOs(nativesDir, "windows"))
        assertFalse(svc.isFolderValidForOs(nativesDir, "linux"))
        assertFalse(svc.isFolderValidForOs(nativesDir, "macos"))
    }

    @Test
    fun `isFolderValidForOs picks up dylib on macOS`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "lwjgl.dylib")
        assertTrue(svc.isFolderValidForOs(nativesDir, "macos"))
    }

    @Test
    fun `isFolderValidForOs returns false for unknown os tag`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "lwjgl.so")
        assertFalse(svc.isFolderValidForOs(nativesDir, "unknown"))
    }

    // ── #185 -- jinput-only must NOT count as valid lwjgl natives ──────────

    @Test
    fun `isFolderValidForOs rejects jinput-only directory (#185)`() {
        // Bug reproducer: lwjgl-platform Maven download silently failed,
        // leaving only the jinput natives. Pre-fix `anyMatch { ends in .so }`
        // returned true -- game then died with UnsatisfiedLinkError.
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "libjinput-linux64.so")
        Files.createFile(nativesDir / "libjinput-linux.so")
        assertFalse(svc.isFolderValidForOs(nativesDir, "linux"),
            "directory missing liblwjgl* must NOT short-circuit prepareNatives")
    }

    @Test
    fun `isFolderValidForOs accepts liblwjgl + liblwjgl64 (LWJGL2 64-bit layout)`() {
        // Real LWJGL 2 lib-name on linux64 -- the modded-MC majority case.
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "liblwjgl.so")
        Files.createFile(nativesDir / "liblwjgl64.so")
        Files.createFile(nativesDir / "libjinput-linux64.so")  // jinput co-exists, not a problem
        assertTrue(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    @Test
    fun `isFolderValidForOs accepts LWJGL3 module layout (liblwjgl-glfw etc)`() {
        // Modern MC (1.13+) uses LWJGL 3 split into modules. Each module is
        // its own native; the gate must still pass on the core liblwjgl.so.
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "liblwjgl.so")
        Files.createFile(nativesDir / "liblwjgl-glfw.so")
        Files.createFile(nativesDir / "liblwjgl-openal.so")
        Files.createFile(nativesDir / "liblwjgl-opengl.so")
        assertTrue(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    @Test
    fun `isFolderValidForOs is case-insensitive on the lwjgl substring`() {
        // Defensive: should the upstream zip ever ship mixed case (Windows
        // FAT32 quirks have historically uppercased filenames) the gate
        // still recognizes the native.
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "LIBLWJGL.SO")
        assertTrue(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    @Test
    fun `isFolderValidForOs rejects directory containing only non-lwjgl natives`() {
        val nativesDir = (workDir / "natives").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "libfoo.so")
        Files.createFile(nativesDir / "libbar.so")
        assertFalse(svc.isFolderValidForOs(nativesDir, "linux"))
    }

    // ── flattenNatives: lift libs from subdirs to root ───────────────────

    @Test
    fun `flattenNatives moves nested so files to dir root`() {
        // LWJGL Maven jars unpack with internal layout like
        // "natives/linux/x64/lwjgl.so" -- the launcher expects them
        // directly at "natives/lwjgl.so" so the JVM can load via
        // -Djava.library.path. flattenNatives walks the tree and
        // hoists matching extensions up.
        val root = (workDir / "natives").also { Files.createDirectories(it) }
        val nested = (root / "linux" / "x64").also { Files.createDirectories(it) }
        Files.createFile(nested / "lwjgl.so")
        Files.createFile(nested / "jinput.so")

        svc.flattenNatives(root)

        assertTrue(Files.exists(root / "lwjgl.so"))
        assertTrue(Files.exists(root / "jinput.so"))
        assertFalse(Files.exists(nested / "lwjgl.so"), "nested copy must be moved, not left dangling")
    }

    @Test
    fun `flattenNatives picks up dll dylib so all in one pass`() {
        // Cross-platform manifest may contain a mixed bag (we don't
        // pre-filter by platform when extracting); flattenNatives must
        // catch all three suffixes regardless of the host's actual OS.
        val root = (workDir / "natives").also { Files.createDirectories(it) }
        val nested = (root / "subdir").also { Files.createDirectories(it) }
        Files.createFile(nested / "lib.so")
        Files.createFile(nested / "lib.dll")
        Files.createFile(nested / "lib.dylib")

        svc.flattenNatives(root)

        assertTrue(Files.exists(root / "lib.so"))
        assertTrue(Files.exists(root / "lib.dll"))
        assertTrue(Files.exists(root / "lib.dylib"))
    }

    @Test
    fun `flattenNatives leaves files already at root untouched`() {
        val root = (workDir / "natives").also { Files.createDirectories(it) }
        Files.writeString(root / "already.so", "content")

        svc.flattenNatives(root)

        assertTrue(Files.exists(root / "already.so"))
        assertEquals("content", Files.readString(root / "already.so"))
    }

    @Test
    fun `flattenNatives ignores non-native files (txt, jar, etc)`() {
        val root = (workDir / "natives").also { Files.createDirectories(it) }
        val nested = (root / "subdir").also { Files.createDirectories(it) }
        Files.createFile(nested / "notes.txt")
        Files.createFile(nested / "lwjgl.jar")
        Files.createFile(nested / "lwjgl.so")

        svc.flattenNatives(root)

        // Only the .so was lifted.
        assertTrue(Files.exists(root / "lwjgl.so"))
        assertFalse(Files.exists(root / "notes.txt"))
        assertFalse(Files.exists(root / "lwjgl.jar"))
        // Non-targets stay where they were.
        assertTrue(Files.exists(nested / "notes.txt"))
        assertTrue(Files.exists(nested / "lwjgl.jar"))
    }

    @Test
    fun `flattenNatives is a no-op on a missing directory`() {
        // Should not throw.
        svc.flattenNatives(workDir / "no-such-dir")
    }

    // ── prepareNatives: short-circuit when already valid ──────────────────

    @Test
    fun `prepareNatives short-circuits when nativesDir already has valid lwjgl native`() {
        // Pre-populate the natives dir with what isFolderValidForOs accepts.
        // No HTTP client is registered for a download, so a fallback fetch
        // would throw -- the test passes only if the short-circuit fires
        // before the Ktor path is reached.
        val clientRoot = workDir
        val nativesDir = (clientRoot / "bin" / "natives-1.7.10").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "liblwjgl.so")

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNatives(
                    clientRoot = clientRoot,
                    nativesDirName = "bin/natives-1.7.10",
                    version = "1.7.10",
                )
            }
        }

        // Pre-existing file untouched.
        assertTrue(Files.exists(nativesDir / "liblwjgl.so"))
    }

    // ── prepareNatives: local zip extraction (no HTTP) ────────────────────

    @Test
    fun `prepareNatives unpacks target-specific local zip from bin`() {
        // Target-specific name is `natives-<version>-<os>.zip`; preferred
        // over the generic `natives-<version>.zip` when both exist.
        val clientRoot = workDir
        val binDir = (clientRoot / "bin").also { Files.createDirectories(it) }
        writeZip(binDir / "natives-1.7.10-linux.zip", mapOf("liblwjgl.so" to "fake-elf".toByteArray()))
        writeZip(binDir / "natives-1.7.10.zip", mapOf("liblwjgl-generic.so" to "should-be-ignored".toByteArray()))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNatives(
                    clientRoot, "bin/natives-1.7.10", "1.7.10",
                )
            }
        }

        val nativesDir = clientRoot / "bin" / "natives-1.7.10"
        assertTrue(Files.exists(nativesDir / "liblwjgl.so"), "target-specific zip should have been used")
        assertFalse(Files.exists(nativesDir / "liblwjgl-generic.so"), "generic zip must NOT be unpacked when target-specific is present")
    }

    @Test
    fun `prepareNatives falls back to generic local zip when target-specific is absent`() {
        val clientRoot = workDir
        val binDir = (clientRoot / "bin").also { Files.createDirectories(it) }
        writeZip(binDir / "natives-1.7.10.zip", mapOf("liblwjgl.so" to "fake-elf".toByteArray()))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNatives(
                    clientRoot, "bin/natives-1.7.10", "1.7.10",
                )
            }
        }

        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.7.10" / "liblwjgl.so"))
    }

    @Test
    fun `prepareNatives cleans pre-existing invalid natives dir before extraction`() {
        // Stale, missing-lwjgl directory triggers full re-extraction. The
        // garbage file must be gone afterwards so a misnamed leftover
        // doesn't poison the classpath / java.library.path the JVM sees.
        val clientRoot = workDir
        val nativesDir = (clientRoot / "bin" / "natives-1.7.10").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "garbage.so")
        val binDir = (clientRoot / "bin")
        writeZip(binDir / "natives-1.7.10.zip", mapOf("liblwjgl.so" to "fake-elf".toByteArray()))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNatives(
                    clientRoot, "bin/natives-1.7.10", "1.7.10",
                )
            }
        }

        assertFalse(Files.exists(nativesDir / "garbage.so"), "stale entry must be cleaned before extraction")
        assertTrue(Files.exists(nativesDir / "liblwjgl.so"), "new content must be extracted")
    }

    @Test
    fun `prepareNatives flattens nested natives lifted by extraction`() {
        // Some natives jars (real LWJGL) put `.so` files under
        // `natives/<os>/<arch>/`. The post-extract flattenNatives step
        // should hoist them to the root so -Djava.library.path picks them
        // up. Combined coverage: extracting + flattening in one pass.
        val clientRoot = workDir
        val binDir = (clientRoot / "bin").also { Files.createDirectories(it) }
        writeZip(binDir / "natives-1.7.10.zip", mapOf(
            "natives/linux/x64/liblwjgl.so" to "fake-elf".toByteArray(),
        ))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNatives(
                    clientRoot, "bin/natives-1.7.10", "1.7.10",
                )
            }
        }

        // Root-level lwjgl is what isFolderValidForOs requires.
        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.7.10" / "liblwjgl.so"),
            "nested .so must be hoisted to the natives root")
    }

    // ── prepareNatives: HTTP download fallback ────────────────────────────

    @Test
    fun `prepareNatives downloads LWJGL2 from Maven for 1_7_10 when no local zip`() {
        // No local zip in bin/ -- forces the legacy LWJGL2 download path.
        // Synthesise a minimal jar (a zip) containing the native, served as
        // bytes from a MockEngine matching lwjgl-platform.
        val clientRoot = workDir
        val lwjglJarBytes = zipBytes(mapOf("liblwjgl.so" to "fake-elf".toByteArray()))
        val provider = mockClientWithBytes(listOf(
            // Both lwjgl-platform and jinput-platform are fetched; serve same
            // contents for both since the test only cares about extraction.
            "lwjgl-platform" to lwjglJarBytes,
            "jinput-platform" to lwjglJarBytes,
        ))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(provider)).prepareNatives(
                    clientRoot, "bin/natives-1.7.10", "1.7.10",
                )
            }
        }

        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.7.10" / "liblwjgl.so"),
            "LWJGL2 download path must populate the natives dir")
    }

    @Test
    fun `prepareNatives downloads LWJGL3 from Maven for 1_21_1 when no local zip`() {
        val clientRoot = workDir
        val lwjglJarBytes = zipBytes(mapOf("liblwjgl.so" to "fake-elf".toByteArray()))
        // LWJGL3 fetches multiple modules; one substring matcher covers them
        // all because they share the `lwjgl-3.3.3` infix.
        val provider = mockClientWithBytes(listOf(
            "lwjgl-3.3.3" to lwjglJarBytes,
        ))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(provider)).prepareNatives(
                    clientRoot, "bin/natives-1.21.1", "1.21.1",
                )
            }
        }

        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.21.1" / "liblwjgl.so"))
    }

    // -- prepareNativesFromManifest: manifest-resolved, version-correct --

    @Test
    fun `prepareNativesFromManifest extracts host natives from resolved jars`() {
        val clientRoot = workDir
        val libs = (clientRoot / "libs").also { Files.createDirectories(it) }
        val jar = libs / "lwjgl-natives-linux.jar"
        writeZip(jar, mapOf(
            "liblwjgl.so" to "elf".toByteArray(),
            "META-INF/MANIFEST.MF" to "x".toByteArray(),
        ))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNativesFromManifest(
                    clientRoot, "bin/natives-1.20.1", listOf(jar),
                )
            }
        }

        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.20.1" / "liblwjgl.so"),
            "the resolved native jar must be unpacked into the instance natives dir")
    }

    @Test
    fun `prepareNativesFromManifest hoists nested natives to the root`() {
        val clientRoot = workDir
        val libs = (clientRoot / "libs").also { Files.createDirectories(it) }
        val jar = libs / "lwjgl-natives-linux.jar"
        // Real LWJGL jars nest the .so under an os/arch path.
        writeZip(jar, mapOf("linux/x64/org/lwjgl/liblwjgl.so" to "elf".toByteArray()))

        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNativesFromManifest(
                    clientRoot, "bin/natives-1.20.1", listOf(jar),
                )
            }
        }

        assertTrue(Files.exists(clientRoot / "bin" / "natives-1.20.1" / "liblwjgl.so"),
            "nested .so must be hoisted to the natives root for java.library.path")
    }

    @Test
    fun `prepareNativesFromManifest short-circuits when the dir is already valid`() {
        val clientRoot = workDir
        val nativesDir = (clientRoot / "bin" / "natives-1.20.1").also { Files.createDirectories(it) }
        Files.createFile(nativesDir / "liblwjgl.so")

        withSystemProp("os.name", "Linux") {
            runBlocking {
                // Bogus jar path -- must NOT be consulted because the dir is already valid.
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNativesFromManifest(
                    clientRoot, "bin/natives-1.20.1", listOf(clientRoot / "does-not-exist.jar"),
                )
            }
        }

        assertTrue(Files.exists(nativesDir / "liblwjgl.so"))
    }

    @Test
    fun `prepareNativesFromManifest does not throw on an empty native list`() {
        val clientRoot = workDir
        withSystemProp("os.name", "Linux") {
            runBlocking {
                EnvironmentPreparer(testTransferEngine(deadHttpClientProvider())).prepareNativesFromManifest(
                    clientRoot, "bin/natives-1.20.1", emptyList(),
                )
            }
        }
        // Logged, not thrown -- and the empty dir is correctly reported invalid.
        assertFalse(svc.isFolderValidForOs(clientRoot / "bin" / "natives-1.20.1", "linux"))
    }

    // ── prepareAssets ─────────────────────────────────────────────────────

    @Test
    fun `prepareAssets unpacks zip when assets dir is missing`() {
        val clientRoot = workDir
        // 12 objects so the post-unpack count check (heuristic threshold 10)
        // accepts the extraction as valid. Production assets archives carry
        // thousands of entries -- this just demonstrates the gate is passable.
        val entries = (1..12).associate { "objects/$it/icon.png" to "img-$it".toByteArray() }
        writeZip(clientRoot / "assets-1.21.zip", entries)

        svc.prepareAssets(clientRoot, "assets-1.21.zip")

        assertTrue(Files.exists(clientRoot / "assets"))
        assertTrue(Files.exists(clientRoot / "assets" / "objects"))
        assertEquals(12, Files.list(clientRoot / "assets" / "objects").use { it.count() })
    }

    @Test
    fun `prepareAssets short-circuits when assets-objects already has enough files`() {
        val clientRoot = workDir
        val objectsDir = (clientRoot / "assets" / "objects").also { Files.createDirectories(it) }
        // 15 sentinel files, more than the 10-file threshold.
        for (i in 1..15) Files.writeString(objectsDir / "user-$i.png", "USER$i")
        // Zip exists but should not be re-extracted on top of the
        // already-populated dir.
        writeZip(clientRoot / "assets-1.21.zip",
            mapOf("objects/replacement/icon.png" to "REPLACE".toByteArray()))

        svc.prepareAssets(clientRoot, "assets-1.21.zip")

        // Pre-existing files are untouched.
        for (i in 1..15) {
            assertEquals("USER$i", Files.readString(objectsDir / "user-$i.png"))
        }
        // Zip content NOT extracted because gate short-circuited.
        assertFalse(Files.exists(objectsDir / "replacement"))
    }

    @Test
    fun `prepareAssets re-unpacks when objects dir has fewer than 10 files`() {
        val clientRoot = workDir
        val objectsDir = (clientRoot / "assets" / "objects").also { Files.createDirectories(it) }
        // 3 files -- under the heuristic threshold, treated as failed prior
        // extraction worth retrying.
        for (i in 1..3) Files.writeString(objectsDir / "sparse-$i.png", "SPARSE$i")
        val entries = (1..12).associate { "objects/full-$it.png" to "FULL$it".toByteArray() }
        writeZip(clientRoot / "assets-1.21.zip", entries)

        svc.prepareAssets(clientRoot, "assets-1.21.zip")

        // New entries from the zip are present.
        assertTrue(Files.exists(objectsDir / "full-1.png"))
    }

    @Test
    fun `prepareAssets falls back to assets-zip when requested name is absent`() {
        val clientRoot = workDir
        // Only the generic assets.zip exists -- the requested name is missing.
        val entries = (1..12).associate { "objects/$it.png" to "X$it".toByteArray() }
        writeZip(clientRoot / "assets.zip", entries)

        svc.prepareAssets(clientRoot, "assets-1.21.zip")

        // Generic was used despite the originally-requested name.
        assertTrue(Files.exists(clientRoot / "assets" / "objects"))
        assertTrue(Files.list(clientRoot / "assets" / "objects").use { it.count() } > 0)
    }

    @Test
    fun `prepareAssets is no-op when neither requested nor fallback zip exists`() {
        val clientRoot = workDir
        // No zips at all. Function must not throw.
        svc.prepareAssets(clientRoot, "assets-1.21.zip")
        assertFalse(Files.exists(clientRoot / "assets"),
            "no input -> no extraction -- assets dir must not appear out of nowhere")
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Build a zip file at [path] containing entries from [files] (path -> bytes).
     * Used to synthesise both natives archives (for prepareNatives) and
     * assets archives (for prepareAssets) without pulling in real artefacts.
     */
    private fun writeZip(path: Path, files: Map<String, ByteArray>) {
        Files.newOutputStream(path).use { os ->
            ZipOutputStream(os).use { zos ->
                for ((name, bytes) in files) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    /** Same as [writeZip] but returns the bytes in-memory for use in HTTP mocks. */
    private fun zipBytes(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * MockEngine-backed provider that matches URLs by substring and serves
     * the corresponding bytes. First match wins; non-matching URLs return
     * 404. Use for prepareNatives' HTTP fallback path tests.
     */
    private fun mockClientWithBytes(matchers: List<Pair<String, ByteArray>>): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val match = matchers.firstOrNull { (substring, _) -> url.contains(substring) }
                    if (match != null) {
                        respond(
                            content = ByteReadChannel(match.second),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                        )
                    } else {
                        respond(
                            content = ByteReadChannel(byteArrayOf()),
                            status = HttpStatusCode.NotFound,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }
                }
            }
        }
        return HttpClientProvider { client }
    }

    /**
     * Provider whose HttpClient throws on any request. Used in tests that
     * expect prepareNatives to short-circuit before any HTTP call -- the
     * thrown exception loudly catches a regression where the short-circuit
     * fails and the code tries to download.
     */
    private fun deadHttpClientProvider(): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    error("unexpected HTTP call -- prepareNatives should have short-circuited")
                }
            }
        }
        return HttpClientProvider { client }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Run [block] with system property [key] temporarily set to [value],
     * restoring the original on exit. Current callers all use `os.name`;
     * generic signature kept for future-proofing.
     */
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
