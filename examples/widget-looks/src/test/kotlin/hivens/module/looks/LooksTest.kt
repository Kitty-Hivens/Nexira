package hivens.module.looks

import hivens.widget.api.ModuleContribution
import hivens.widget.api.ParticleContribution
import hivens.widget.api.ParticlePalette
import hivens.widget.api.ThemeContribution
import hivens.widget.loader.WidgetModuleLoader
import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The jar is a module the launcher's loader accepts, what it brings is sound, and every key has words in every language. */
class LooksTest {

    private val fields = ServiceLoader.load(ParticleContribution::class.java).toList()

    @Test
    fun `the jar is a module with two themes, two fields and no widgets`() {
        val jar = Path.of(requireNotNull(System.getProperty("nexira.test.moduleJar")))
        val dir = Files.createTempDirectory("looks-")
        Files.copy(jar, dir.resolve("looks.jar"))
        val scan = WidgetModuleLoader(dir, parent = WithoutThisModule(javaClass.classLoader)).scan()
        try {
            assertEquals(emptyList(), scan.rejected)
            val module = scan.loaded.single()
            assertEquals(emptyMap(), module.registry.all())
            assertEquals(setOf("outrun", "paperback"), module.contributions.filterIsInstance<ThemeContribution>().map { it.id }.toSet())
            assertEquals(setOf("fireflies", "confetti"), module.contributions.filterIsInstance<ParticleContribution>().map { it.id }.toSet())
            assertEquals(setOf("en", "ru", "de", "ja"), module.strings.languages)
        } finally {
            scan.loaded.forEach { it.loader.close() }
            Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `every mote stays in the area, is visible at most fully, and is the same on every run`() {
        fields.forEach { field ->
            for (i in 0 until 200) {
                for (t in listOf(0f, 1.5f, 40f, 3_000f)) {
                    val m = field.moteAt(i, t, 800f, 600f)
                    assertTrue(m.x in 0f..800f && m.y in 0f..600f, "${field.id} mote $i at $t is outside the area: ${m.x}, ${m.y}")
                    assertTrue(m.alpha in 0f..1f && m.radius > 0f, "${field.id} mote $i at $t has alpha ${m.alpha} radius ${m.radius}")
                    assertEquals(m, field.moteAt(i, t, 800f, 600f))
                }
            }
        }
    }

    @Test
    fun `every field moves with time and has a colour to draw from`() {
        fields.forEach { field ->
            val palette = field.palette(ParticlePalette(Color.White, Color.Gray, listOf(Color.Red, Color.Blue)))
            assertTrue(palette.isNotEmpty(), field.id)
            assertTrue((0 until 50).any { field.moteAt(it, 0f, 800f, 600f) != field.moteAt(it, 2f, 800f, 600f) }, "${field.id} holds still")
        }
    }

    @Test
    fun `every label has words in every language the module ships`() {
        val contributions = ServiceLoader.load(ThemeContribution::class.java).toList<ModuleContribution>() + fields
        listOf("en", "ru", "de", "ja").forEach { tag ->
            val table = Properties().apply {
                LooksTest::class.java.classLoader.getResourceAsStream("i18n/$tag.properties")!!.reader(Charsets.UTF_8).use { load(it) }
            }
            assertEquals(emptySet(), contributions.map { it.label }.toSet() - table.stringPropertyNames(), "keys missing in $tag")
        }
    }
}

/** The test classpath carries this module's own classes, which a parent-first loader would find there and never open the jar. */
private class WithoutThisModule(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("hivens.module.looks.")) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }
}
