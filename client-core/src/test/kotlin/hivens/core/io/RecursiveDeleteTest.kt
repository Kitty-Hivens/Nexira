package hivens.core.io

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecursiveDeleteTest {

    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("nexira-delete-tree-test-")
    }

    @AfterTest
    fun teardown() {
        // Not deleteTree: teardown must survive a test that left the tree in a
        // state deleteTree itself would choke on.
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /**
     * Creating a symlink needs a privilege Windows does not grant by default,
     * so the link-specific cases report whether they could run at all rather
     * than passing vacuously on a runner that never made one.
     */
    private fun symlinkOrNull(link: Path, target: Path): Path? =
        runCatching { Files.createSymbolicLink(link, target) }.getOrNull()

    @Test
    fun `an ordinary tree is removed`() {
        val tree = root.resolve("instance")
        Files.createDirectories(tree.resolve("mods"))
        Files.writeString(tree.resolve("mods/a.jar"), "a")
        Files.writeString(tree.resolve("options.txt"), "b")

        deleteTree(tree)

        assertFalse(Files.exists(tree))
    }

    @Test
    fun `a symlinked directory is unlinked, not emptied`() {
        // The shape that costs data: a user points mods/ at a shared folder,
        // then removes the instance.
        val outside = Files.createDirectory(root.resolve("shared-mods"))
        val keep = outside.resolve("precious.jar")
        Files.writeString(keep, "not yours to delete")

        val tree = Files.createDirectory(root.resolve("instance"))
        val link = symlinkOrNull(tree.resolve("mods"), outside) ?: return

        deleteTree(tree)

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link itself must be gone")
        assertFalse(Files.exists(tree), "the instance directory must be gone")
        assertTrue(Files.exists(keep), "the link target's contents were deleted through the link")
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `a symlinked file is unlinked, not followed`() {
        val outside = Files.writeString(root.resolve("outside.txt"), "keep me")
        val tree = Files.createDirectory(root.resolve("instance2"))
        val link = symlinkOrNull(tree.resolve("linked.txt"), outside) ?: return

        deleteTree(tree)

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(outside), "the link target was deleted through the link")
    }

    @Test
    fun `a missing path is a no-op`() {
        deleteTree(root.resolve("never-existed"))
    }

    @Test
    fun `a dangling symlink is removed rather than tripping the walk`() {
        val tree = Files.createDirectory(root.resolve("instance3"))
        val link = symlinkOrNull(tree.resolve("gone"), root.resolve("no-such-target")) ?: return

        deleteTree(tree)

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(tree))
    }
}
