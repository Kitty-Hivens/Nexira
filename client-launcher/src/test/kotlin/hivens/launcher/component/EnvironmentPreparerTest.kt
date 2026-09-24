package hivens.launcher.component

import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue

class EnvironmentPreparerTest {

    private lateinit var workDir: Path
    private lateinit var svc: EnvironmentPreparer

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-envprep-test-")
        svc = EnvironmentPreparer()
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
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

    /**
     * Rebuilt for a bound launch, the folder was cleared one level deep and the
     * flatten then moved a file from a subdirectory over what had been unpacked.
     */
    @Test
    fun `a rebuild leaves nothing from a subdirectory to be flattened over the unpacked natives`() = runBlocking {
        val jar = nativeJar("lwjgl-natives.jar", "genuine".toByteArray())
        val nested = (workDir / "bin/natives/deep").also { Files.createDirectories(it) }
        Files.write(nested / hostNativeName(), "planted".toByteArray())

        svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(jar), rebuild = true)

        assertEquals("genuine", Files.readString(workDir / "bin/natives" / hostNativeName()))
    }

    /** A partial folder passed the one-lwjgl-file check and the game died on the missing library. */
    @Test
    fun `an extraction that died partway is not trusted by the next launch`() = runBlocking {
        val jar = nativeJar("lwjgl-natives.jar", "genuine".toByteArray())
        val nativesDir = (workDir / "bin/natives").also { Files.createDirectories(it) }
        Files.write(nativesDir / hostNativeName(), "partial".toByteArray())
        Files.writeString(nativesDir / ".nexira-extracting", "")

        svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(jar), rebuild = false)

        assertEquals("genuine", Files.readString(nativesDir / hostNativeName()))
        assertFalse(Files.exists(nativesDir / ".nexira-extracting"))
    }

    @Test
    fun `a native jar that will not unpack stops the launch instead of spawning a game that cannot load`() = runBlocking {
        val broken = (workDir / "broken.jar").also { Files.write(it, "not a zip".toByteArray()) }

        val failure = runCatching { svc.prepareNativesFromManifest(workDir, "bin/natives", listOf(broken), rebuild = true) }.exceptionOrNull()

        assertTrue(failure is IOException, "got $failure")
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

    // ── helpers ───────────────────────────────────────────────────────────

}
