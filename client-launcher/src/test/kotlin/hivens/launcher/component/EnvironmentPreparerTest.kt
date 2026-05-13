package hivens.launcher.component

import hivens.test.buildMockClient
import java.nio.file.Files
import java.nio.file.Path
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
        svc = EnvironmentPreparer(buildMockClient(""))
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── getOsSuffix: os.name → LWJGL Maven Central classifier suffix ─────

    @Test
    fun `getOsSuffix maps Linux variants to linux`() {
        withSystemProp("os.name", "Linux") {
            assertEquals("linux", svc.getOsSuffix())
        }
    }

    @Test
    fun `getOsSuffix maps Windows variants to windows`() {
        withSystemProp("os.name", "Windows 11") {
            assertEquals("windows", svc.getOsSuffix())
        }
        withSystemProp("os.name", "Windows 10") {
            assertEquals("windows", svc.getOsSuffix())
        }
    }

    @Test
    fun `getOsSuffix maps macOS to macos`() {
        // Note: this is the LWJGL 3 form ("macos"). The legacy LWJGL 2
        // form ("macosx") is handled by downloadLegacyLWJGL2 internally.
        withSystemProp("os.name", "Mac OS X") {
            assertEquals("macos", svc.getOsSuffix())
        }
    }

    @Test
    fun `getOsSuffix returns unknown for unrecognised OSes`() {
        withSystemProp("os.name", "Plan9") {
            assertEquals("unknown", svc.getOsSuffix())
        }
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
        // Asking for linux — only .so counts.
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

    // ── flattenNatives: lift libs from subdirs to root ───────────────────

    @Test
    fun `flattenNatives moves nested so files to dir root`() {
        // LWJGL Maven jars unpack with internal layout like
        // "natives/linux/x64/lwjgl.so" — the launcher expects them
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

        // Only the .so was lifted up.
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
