package hivens.ui.host

import hivens.core.data.ModuleStore
import hivens.core.data.NewerBuildData
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The data directory as the modules compiled in write into it: inside it only, and a newer build's file named by its module. */
class LauncherFilesTest {

    private val root: Path = Files.createTempDirectory("launcher-files")
    private val data: Path = root.resolve("data").createDirectories()
    private val outside: Path = root.resolve("outside").createDirectories()
    private val files = LauncherFiles(data)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        NewerBuildData.reset()
        root.deleteRecursively()
    }

    @Test
    fun `a file inside the data directory is written, its folders made`() {
        files.publish(data.resolve("module").resolve("file.json"), "{}")
        assertEquals("{}", data.resolve("module").resolve("file.json").readText())
    }

    @Test
    fun `a file outside the data directory is refused, however it is spelled`() {
        assertFailsWith<IllegalArgumentException> { files.publish(outside.resolve("file.json"), "{}") }
        assertFailsWith<IllegalArgumentException> { files.publish(data.resolve("..").resolve("outside").resolve("file.json"), "{}") }
        assertFailsWith<IllegalArgumentException> { files.publish(data, "{}") }
        assertFalse(outside.resolve("file.json").exists())
    }

    @Test
    fun `a link inside the data directory that leads out of it is refused, a folder or the file itself`() {
        Files.createSymbolicLink(data.resolve("away"), outside)
        Files.createSymbolicLink(data.resolve("theirs.json"), outside.resolve("theirs.json"))
        Files.writeString(outside.resolve("theirs.json"), "kept")

        assertFailsWith<IllegalArgumentException> { files.publish(data.resolve("away").resolve("file.json"), "{}") }
        assertFailsWith<IllegalArgumentException> { files.publish(data.resolve("theirs.json"), "{}") }
        assertFalse(outside.resolve("file.json").exists())
        assertEquals("kept", outside.resolve("theirs.json").readText())
    }

    @Test
    fun `a file a newer build wrote is put on record under the name its module gives it`() {
        NewerBuildData.reset()
        files.newerBuild(data.resolve("whatever.json"), "test.module", "test.file")
        assertEquals(setOf(ModuleStore("test.module", "test.file")), NewerBuildData.modulesWithReason().keys)
        assertTrue(NewerBuildData.affected().isEmpty())
    }
}
