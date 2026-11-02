package hivens.module.screenfx

import hivens.widget.loader.WidgetModuleLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The jar this module builds is a module the launcher's own loader accepts, with its effects and no widgets. */
class PackagingTest {

    @Test
    fun `the launcher's loader opens the jar and finds the three effects`() {
        val jar = Path.of(requireNotNull(System.getProperty("nexira.test.moduleJar")) { "the module jar was not passed to the test JVM" })
        val dir = Files.createTempDirectory("screenfx-")
        Files.copy(jar, dir.resolve("screenfx.jar"))
        val scan = WidgetModuleLoader(dir, parent = WithoutThisModule(javaClass.classLoader)).scan()
        try {
            assertEquals(emptyList(), scan.rejected)
            val module = scan.loaded.single()
            assertEquals("screenfx", module.id)
            assertEquals(emptyMap(), module.registry.all())
            assertEquals(setOf("screenfx.trip", "screenfx.retro", "screenfx.glitch"), module.effects.map { it.id }.toSet())
            assertTrue(module.effects.all { it.javaClass.classLoader === module.loader }, "an effect resolved through the launcher's loader")
        } finally {
            scan.loaded.forEach { it.loader.close() }
            Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/**
 * The test classpath carries this module's own classes, and a loader that delegates to its parent
 * first would find them there and never open the jar. This parent answers for everything but the
 * module, which is what the launcher's loader looks like to a jar that is not on its classpath.
 */
private class WithoutThisModule(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("hivens.module.screenfx.")) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }
}
