package hivens.launcher.instance

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceContentManagerTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun instance(): Path = Files.createTempDirectory("content").also { temps.add(it) }.also {
        Files.createDirectories(it.resolve("mods"))
    }

    private val manager = InstanceContentManager()

    /** An atomic move replaces its target, so enabling put the stale copy over the jar in use. */
    @Test
    fun `enabling leaves a jar already under the loadable name alone`() = runTest {
        val dir = instance()
        Files.writeString(dir.resolve("mods/x.jar"), "CURRENT")
        Files.writeString(dir.resolve("mods/x.jar.disabled"), "STALE")

        manager.setEnabled(dir, ContentKind.Mod, "x.jar", enabled = true)

        assertEquals("CURRENT", dir.resolve("mods/x.jar").readText())
    }

    @Test
    fun `disabling with both names present keeps the loaded jar's bytes`() = runTest {
        val dir = instance()
        Files.writeString(dir.resolve("mods/x.jar"), "CURRENT")
        Files.writeString(dir.resolve("mods/x.jar.disabled"), "STALE")

        manager.setEnabled(dir, ContentKind.Mod, "x.jar", enabled = false)

        assertFalse(Files.exists(dir.resolve("mods/x.jar")))
        assertEquals("CURRENT", dir.resolve("mods/x.jar.disabled").readText())
    }

    /** Copied straight to the final name, an interrupted copy published a truncated jar there for good. */
    @Test
    fun `a copy that fails publishes nothing under the final name`() = runTest {
        val dir = instance()
        val missing = dir.resolve("nowhere/y.jar")

        val added = manager.addFiles(dir, ContentKind.Mod, listOf(missing))

        assertEquals(0, added)
        assertFalse(Files.exists(dir.resolve("mods/y.jar")))
        assertTrue(Files.list(dir.resolve("mods")).use { it.count() } == 0L, "and no staged copy is left behind")
    }

    @Test
    fun `an added file lands whole under its own name`() = runTest {
        val dir = instance()
        val src = Files.writeString(Files.createTempFile("y", ".jar").also { temps.add(it) }, "WHOLE")

        assertEquals(1, manager.addFiles(dir, ContentKind.Mod, listOf(src)))
        assertEquals("WHOLE", dir.resolve("mods/${src.fileName}").readText())
    }

    /**
     * An update takes the new file's name from the catalogue. Inside the folder is
     * not enough: `1.12.2/x.jar` resolves under `mods/` and lands where Forge reads
     * a second set of mods, beside the build it was meant to replace.
     */
    @Test
    fun `a replacement whose name is a path is refused and the old file stays`() = runTest {
        val dir = instance()
        // Forge 1.12.2 makes this directory itself, so it is there to land in.
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        Files.writeString(dir.resolve("mods/x.jar"), "OLD")
        val scratch = Files.writeString(dir.resolve("mods/.nexira-update-1.part"), "NEW")

        val landed = manager.replace(dir, ContentKind.Mod, "x.jar", scratch, "1.12.2/x.jar", enabled = true)

        assertFalse(landed)
        assertEquals("OLD", dir.resolve("mods/x.jar").readText())
        assertFalse(Files.exists(dir.resolve("mods/1.12.2/x.jar")))
        assertFalse(Files.exists(scratch), "the download goes with the refusal")
    }

    /** Listed twice, the two rows carried one identity and Compose rejected the duplicate key. */
    @Test
    fun `an item on disk under both names is listed once, as the loadable one`() = runTest {
        val dir = instance()
        for (name in listOf("x.jar", "x.jar.disabled")) {
            ZipOutputStream(Files.newOutputStream(dir.resolve("mods/$name"))).use { zos ->
                zos.putNextEntry(ZipEntry("fabric.mod.json")); zos.write("""{"name":"X","version":"1"}""".toByteArray()); zos.closeEntry()
            }
        }

        val items = InstanceContentScanner().scan(dir)

        assertEquals(1, items.size, "$items")
        assertTrue(items.single().enabled)
    }
}
