package hivens.launcher.util

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * cleanDirectory deletes user files (mods / natives sync), so its keep/delete
 * decision is high-consequence: it must remove only redundant loadable
 * artifacts and never touch config or subdirectories.
 */
class ClientFileHelperTest {

    private val log = LoggerFactory.getLogger(ClientFileHelperTest::class.java)
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("client-file-helper-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(name: String): Path =
        Files.write(dir.resolve(name), byteArrayOf(1))

    @Test
    fun `ensureDirectoryExists creates a missing nested directory`() {
        val nested = dir.resolve("a/b/c")
        ClientFileHelper.ensureDirectoryExists(nested)
        assertTrue(Files.isDirectory(nested))
    }

    @Test
    fun `ensureDirectoryExists is a no-op on an existing directory`() {
        ClientFileHelper.ensureDirectoryExists(dir) // must not throw
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun `cleanDirectory keeps allowed loadable files and deletes the rest`() {
        val keep = touch("keep.jar")
        val drop = touch("drop.jar")
        ClientFileHelper.cleanDirectory(dir, allowedFiles = setOf("keep.jar"), logger = log)
        assertTrue(Files.exists(keep), "allowed jar must survive")
        assertFalse(Files.exists(drop), "unlisted jar must be deleted")
    }

    @Test
    fun `cleanDirectory deletes every loadable extension when unlisted`() {
        val files = listOf("a.jar", "b.zip", "c.litemod", "d.dll", "e.so", "f.dylib").map { touch(it) }
        ClientFileHelper.cleanDirectory(dir, allowedFiles = emptySet(), logger = log)
        for (f in files) assertFalse(Files.exists(f), "should delete loadable: ${f.fileName}")
    }

    @Test
    fun `cleanDirectory never touches non-loadable extensions`() {
        val config = touch("options.txt")
        val json = touch("servers.json")
        val noExt = touch("README")
        ClientFileHelper.cleanDirectory(dir, allowedFiles = emptySet(), logger = log)
        assertTrue(Files.exists(config), "config files must be preserved")
        assertTrue(Files.exists(json))
        assertTrue(Files.exists(noExt))
    }

    @Test
    fun `cleanDirectory ignores subdirectories even with loadable-looking names`() {
        val subdir = Files.createDirectory(dir.resolve("nested.jar"))
        ClientFileHelper.cleanDirectory(dir, allowedFiles = emptySet(), logger = log)
        assertTrue(Files.isDirectory(subdir), "a directory named *.jar must not be deleted")
    }

    @Test
    fun `cleanDirectory on a missing directory is a silent no-op`() {
        ClientFileHelper.cleanDirectory(dir.resolve("does-not-exist"), allowedFiles = emptySet(), logger = log)
        // no throw == pass
    }
}
