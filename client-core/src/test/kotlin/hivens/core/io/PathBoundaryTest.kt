package hivens.core.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathBoundaryTest {

    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-path-boundary-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `an ordinary entry resolves under the root`() {
        assertEquals(root.resolve("mods/foo.jar").normalize(), resolveWithinRoot(root, "mods/foo.jar"))
    }

    @Test
    fun `a nested traversal that ends up back inside is allowed`() {
        // Ugly but harmless -- it lands under the root, which is the only
        // question being asked.
        assertEquals(root.resolve("config/foo.cfg").normalize(), resolveWithinRoot(root, "mods/../config/foo.cfg"))
    }

    @Test
    fun `an entry escaping through dot-dot is refused`() {
        // The shape a hostile file manifest produces: a first segment that
        // passes for a known root directory, then a climb out.
        assertFailsWith<IOException> { resolveWithinRoot(root, "mods/../../../.bashrc") }
    }

    @Test
    fun `a leading dot-dot is refused`() {
        assertFailsWith<IOException> { resolveWithinRoot(root, "../evil") }
    }

    @Test
    fun `an absolute entry is refused`() {
        // Path.resolve replaces the root outright when handed an absolute path,
        // which is the quietest escape of the lot.
        assertFailsWith<IOException> { resolveWithinRoot(root, "/etc/passwd") }
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is refused`() {
        // startsWith on paths compares whole segments, so this must not pass
        // just because the strings share a prefix.
        assertFailsWith<IOException> { resolveWithinRoot(root, "../${root.fileName}-other/x") }
    }

    @Test
    fun `the failure names the entry as the server wrote it`() {
        val raw = "Industrial/mods/../../../.bashrc"
        val message = assertFailsWith<IOException> { resolveWithinRoot(root, "mods/../../../.bashrc", raw) }.message
        assertEquals(true, message?.contains(raw), "the refusal must name the manifest entry, got: $message")
    }
}
