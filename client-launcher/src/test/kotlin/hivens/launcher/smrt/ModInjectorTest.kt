package hivens.launcher.smrt

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModInjectorTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("modinjector-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(rel: String) {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, "x")
    }

    @Test
    fun `injectHelperJar copies the helper into mods, overwriting`() {
        val helper = dir.resolve("helpers").resolve("open-smrt-network-1.12.2.jar")
        Files.createDirectories(helper.parent)
        Files.writeString(helper, "helper-v2")
        // a stale prior copy must be replaced
        touch("mods/open-smrt-network-1.12.2.jar")

        ModInjector.injectHelperJar(dir, helper)

        val dest = dir.resolve("mods").resolve("open-smrt-network-1.12.2.jar")
        assertTrue(Files.isRegularFile(dest))
        assertEquals("helper-v2", Files.readString(dest), "inject must overwrite the stale jar")
    }

    @Test
    fun `stripByGlobs removes matching jars in top-level and version subdirs, keeps others`() {
        touch("mods/Smarty.jar")
        touch("mods/1.12.2/SmartyClient.jar")
        touch("mods/JEI.jar")
        touch("mods/1.12.2/Mixinbooter.jar")

        val removed = ModInjector.stripByGlobs(dir, listOf("Smarty*.jar"))

        assertEquals(2, removed, "both Smarty jars should be stripped across dirs")
        assertFalse(Files.exists(dir.resolve("mods/Smarty.jar")))
        assertFalse(Files.exists(dir.resolve("mods/1.12.2/SmartyClient.jar")))
        assertTrue(Files.exists(dir.resolve("mods/JEI.jar")), "unrelated mods must be kept")
        assertTrue(Files.exists(dir.resolve("mods/1.12.2/Mixinbooter.jar")), "unrelated mods must be kept")
    }

    @Test
    fun `stripByGlobs is a no-op with empty globs or no mods dir`() {
        assertEquals(0, ModInjector.stripByGlobs(dir, emptyList()))
        touch("mods/JEI.jar")
        assertEquals(0, ModInjector.stripByGlobs(dir, emptyList()))
        assertTrue(Files.exists(dir.resolve("mods/JEI.jar")))
    }
}
