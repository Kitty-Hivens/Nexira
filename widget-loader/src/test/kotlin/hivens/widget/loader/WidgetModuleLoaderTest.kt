package hivens.widget.loader

import hivens.widget.api.WidgetApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the loader against a real widget module jar, built by the
 * fixtureModule source set exactly the way a third party would build one.
 *
 * That jar is deliberately absent from this test's classpath. A fixture the
 * tests could already see would be resolved through the parent loader and every
 * one of these would pass without the jar ever being opened.
 */
class WidgetModuleLoaderTest {

    private lateinit var tmp: Path
    private lateinit var modules: Path

    private val fixtureJar: Path =
        Path.of(requireNotNull(System.getProperty("nexira.test.fixtureModuleJar")) {
            "the fixture module jar was not passed to the test JVM"
        })

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("widget-loader-test-")
        modules = tmp.resolve("modules").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `a well-formed module is loaded, named, and brings its widgets`() {
        install("good.jar")

        val scan = WidgetModuleLoader(modules).scan()

        assertEquals(emptyList(), scan.rejected)
        val module = scan.loaded.single()
        assertEquals("fixture", module.id)
        assertEquals("Fixture Module", module.name)
        assertEquals(setOf("fixture.widget"), module.registry.all().keys.map { it.value }.toSet())
    }

    @Test
    fun `the registry comes out of the jar, not off the test classpath`() {
        install("good.jar")

        val registry = WidgetModuleLoader(modules).scan().loaded.single().registry

        assertEquals("fixture.FixtureRegistry", registry.javaClass.name)
        assertTrue(
            registry.javaClass.classLoader !== javaClass.classLoader,
            "the fixture resolved through the parent loader, so this test proves nothing",
        )
    }

    @Test
    fun `two modules are loaded independently of each other`() {
        install("a.jar", id = "first")
        install("b.jar", id = "second")

        val loaded = WidgetModuleLoader(modules).scan().loaded

        assertEquals(listOf("first", "second"), loaded.map { it.id })
        assertTrue(
            loaded[0].registry.javaClass.classLoader !== loaded[1].registry.javaClass.classLoader,
            "modules must not share a class loader, or a class name collision becomes a hijack",
        )
    }

    @Test
    fun `a module built for another ABI is refused, and the reason names both versions`() {
        install("old.jar", api = WidgetApi.VERSION + 1)

        val scan = WidgetModuleLoader(modules).scan()

        assertEquals(emptyList(), scan.loaded)
        val reason = scan.rejected.single().reason
        assertTrue("${WidgetApi.VERSION + 1}" in reason && "${WidgetApi.VERSION}" in reason, reason)
    }

    @Test
    fun `a jar that is not a widget module at all is refused as such`() {
        install("plain.jar", api = null)

        val rejected = WidgetModuleLoader(modules).scan().rejected.single()

        assertTrue(WidgetApi.MANIFEST_VERSION in rejected.reason, rejected.reason)
    }

    @Test
    fun `a version that is not a number is refused rather than defaulted`() {
        install("weird.jar", apiText = "1.0-SNAPSHOT")

        val rejected = WidgetModuleLoader(modules).scan().rejected.single()

        assertTrue("1.0-SNAPSHOT" in rejected.reason, rejected.reason)
    }

    @Test
    fun `a module with no id is refused`() {
        install("anonymous.jar", id = null)

        val rejected = WidgetModuleLoader(modules).scan().rejected.single()

        assertTrue(WidgetApi.MANIFEST_ID in rejected.reason, rejected.reason)
    }

    @Test
    fun `a module with no name is known by its id`() {
        install("plain-name.jar", name = null)

        assertEquals("fixture", WidgetModuleLoader(modules).scan().loaded.single().name)
    }

    @Test
    fun `a module declaring the API but carrying no registry is refused`() {
        install("empty.jar", withService = false)

        val rejected = WidgetModuleLoader(modules).scan().rejected.single()

        assertTrue("no registry" in rejected.reason, rejected.reason)
    }

    @Test
    fun `a damaged jar is refused rather than taking the scan down with it`() {
        modules.resolve("broken.jar").writeText("this is not a zip")
        install("good.jar")

        val scan = WidgetModuleLoader(modules).scan()

        assertEquals(1, scan.loaded.size, "one bad file must not cost the others")
        assertEquals(1, scan.rejected.size)
    }

    @Test
    fun `files that are not jars are ignored silently`() {
        modules.resolve("notes.txt").writeText("hello")
        modules.resolve("subdir").createDirectories()

        assertEquals(WidgetModuleScan(), WidgetModuleLoader(modules).scan())
    }

    @Test
    fun `a missing directory is a launcher with no modules, not an error`() {
        assertEquals(WidgetModuleScan(), WidgetModuleLoader(tmp.resolve("nothing-here")).scan())
    }

    // -- fixture ------------------------------------------------------------

    /**
     * Copies the built fixture module into the scan directory, optionally with a
     * damaged manifest or its service entry withheld. Everything else about the
     * jar -- the compiled registry, the descriptor it carries -- is genuine.
     */
    private fun install(
        fileName: String,
        id: String? = "fixture",
        name: String? = "Fixture Module",
        api: Int? = WidgetApi.VERSION,
        apiText: String? = null,
        withService: Boolean = true,
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            (apiText ?: api?.toString())?.let { mainAttributes.putValue(WidgetApi.MANIFEST_VERSION, it) }
            id?.let { mainAttributes.putValue(WidgetApi.MANIFEST_ID, it) }
            name?.let { mainAttributes.putValue(WidgetApi.MANIFEST_NAME, it) }
        }
        JarFile(fixtureJar.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(modules.resolve(fileName)), manifest).use { out ->
                source.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }
                    .filterNot { !withService && it.name.startsWith("META-INF/services/") }
                    .forEach { entry ->
                        out.putNextEntry(JarEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
        }
    }
}
