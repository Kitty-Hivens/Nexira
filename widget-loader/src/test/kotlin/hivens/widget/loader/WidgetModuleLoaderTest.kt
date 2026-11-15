package hivens.widget.loader

import androidx.compose.runtime.Composer
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemesApi
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.ModuleValues
import hivens.widget.api.ScreenEffect
import hivens.widget.api.WidgetApi
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ApiVersion
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.io.InputStream
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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

    /** Every module a scan in this test opened, so teardown can let its jar go. */
    private val opened = mutableListOf<LoadedWidgetModule>()

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
        // A loaded module holds its jar open. Two of the three hosts will unlink an
        // open file and the third will not, so releasing first is what makes this
        // teardown mean the same thing everywhere.
        opened.forEach { val _ = runCatching { it.loader.close() } }
        opened.clear()
        Files.walk(tmp).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Scans as a launcher that carries the themes module does: its API offered and shown by its
     * own package, and its kind of contribution read beside the kernel's [kinds].
     */
    private fun themesScan(
        kinds: List<Class<out ModuleContribution>> = ModuleContribution.kinds + ThemeContribution::class.java,
    ): WidgetModuleScan =
        WidgetModuleLoader(
            modules,
            contributionKinds = kinds,
            offered = mapOf(ThemesApi.ID to ThemesApi.VERSION),
            apiPackages = mapOf(ThemesApi.ID to setOf(ThemesApi::class.java.name.substringBeforeLast('.'))),
        ).scan().also { opened += it.loaded }

    /** Scans, remembering what it opened, with each API [offered] sharing a package of its own. */
    private fun scan(dir: Path = modules, offered: Map<String, ApiVersion> = emptyMap()): WidgetModuleScan =
        WidgetModuleLoader(dir, offered = offered, apiPackages = offered.mapValues { (id, _) -> setOf("fixture.api.$id") }).scan()
            .also { opened += it.loaded }

    @Test
    fun `a well-formed module is loaded, named, and brings its widgets`() {
        install("good.jar")

        val scan = scan()

        assertEquals(emptyList(), scan.rejected)
        val module = scan.loaded.single()
        assertEquals("fixture", module.id)
        assertEquals("Fixture Module", module.name)
        assertEquals(setOf("fixture.widget"), module.registry.all().keys.map { it.value }.toSet())
    }

    @Test
    fun `the registry comes out of the jar, not off the test classpath`() {
        install("good.jar")

        val registry = scan().loaded.single().registry

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

        val loaded = scan().loaded

        assertEquals(listOf("first", "second"), loaded.map { it.id })
        assertTrue(
            loaded[0].registry.javaClass.classLoader !== loaded[1].registry.javaClass.classLoader,
            "modules must not share a class loader, or a class name collision becomes a hijack",
        )
    }

    @Test
    fun `a module built for another ABI is refused, and the reason names both versions`() {
        install("old.jar", api = WidgetApi.VERSION + 1)

        val scan = scan()

        assertEquals(emptyList(), scan.loaded)
        val reason = scan.rejected.single().reason
        assertTrue("${WidgetApi.VERSION + 1}" in reason && "${WidgetApi.VERSION}" in reason, reason)
    }

    @Test
    fun `a jar that is not a widget module at all is refused as such`() {
        install("plain.jar", api = null)

        val rejected = scan().rejected.single()

        assertTrue(WidgetApi.MANIFEST_VERSION in rejected.reason, rejected.reason)
    }

    @Test
    fun `a version that is not a number is refused rather than defaulted`() {
        install("weird.jar", apiText = "1.0-SNAPSHOT")

        val rejected = scan().rejected.single()

        assertTrue("1.0-SNAPSHOT" in rejected.reason, rejected.reason)
    }

    @Test
    fun `a module with no id is refused`() {
        install("anonymous.jar", id = null)

        val rejected = scan().rejected.single()

        assertTrue(WidgetApi.MANIFEST_ID in rejected.reason, rejected.reason)
    }

    @Test
    fun `a module with no name is known by its id`() {
        install("plain-name.jar", name = null)

        assertEquals("fixture", scan().loaded.single().name)
    }

    @Test
    fun `a module declaring the API but carrying no service at all is refused`() {
        install("empty.jar", withService = false)

        val rejected = scan().rejected.single()

        assertTrue("neither a registry" in rejected.reason, rejected.reason)
    }

    @Test
    fun `a module brings its screen effects out of its own jar`() {
        install("good.jar")

        val effect = scan().loaded.single().effects.single()

        assertEquals("fixture.effect", effect.id)
        assertEquals("fixture.label", effect.label)
        assertTrue(effect.javaClass.classLoader !== javaClass.classLoader, "the effect resolved through the parent loader")
    }

    @Test
    fun `a module that carries only screen effects is a module with no widgets`() {
        install("effects-only.jar", withRegistryService = false)

        val scan = scan()

        assertEquals(emptyList(), scan.rejected)
        val module = scan.loaded.single()
        assertEquals(emptyMap(), module.registry.all())
        assertEquals(listOf("fixture.effect"), module.effects.map { it.id })
    }

    @Test
    fun `a screen effect with no id is refused, not offered under a blank name`() {
        install("good.jar")
        System.setProperty("fixture.effectBlankId", "1")
        val scan = try {
            scan()
        } finally {
            System.clearProperty("fixture.effectBlankId")
        }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("no id" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a screen effect carries its settings with their declared defaults`() {
        install("good.jar")

        val effect = scan().loaded.single().effects.single()

        assertEquals(listOf("flag", "amount"), effect.settings.map { it.key })
        assertEquals(0.25f, ModuleValues(effect.settings).amount("amount"))
    }

    @Test
    fun `a setting key declared twice refuses the module`() {
        install("good.jar")

        val scan = withProperty("fixture.effectRepeatedKey") { scan() }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("share the key 'flag'" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a setting named like the launcher's own key refuses the module`() {
        install("good.jar")

        val scan = withProperty("fixture.effectReservedKey") { scan() }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("enabled" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a module brings its words per language, read from its own jar`() {
        install("good.jar")

        val strings = scan().loaded.single().strings

        assertEquals(setOf("en", "ru"), strings.languages)
        assertEquals("Тестовый эффект", strings.tables.getValue("ru")["fixture.label"])
        assertEquals("Fixture Effect", strings.tables.getValue("en")["fixture.label"])
        assertEquals("Sum {0} and {1}", strings.tables.getValue("en")["fixture.sum"], "read as written, for the language module to format")
    }

    @Test
    fun `words a module carries for another namespace are read under that namespace`() {
        install("good.jar")

        val strings = scan().loaded.single().strings

        assertEquals(setOf("nexira"), strings.namespaces.keys)
        assertEquals("Vom Fixture zu den Worten des Launchers gelegt", strings.namespaces.getValue("nexira").getValue("de")["fixture.inLauncher"])
        assertTrue("de" !in strings.languages, "they are not the module's own words")
    }

    @Test
    fun `a language a module adds is read from its declaration, and is not taken for a namespace`() {
        install("good.jar")

        val strings = scan().loaded.single().strings

        assertEquals(mapOf("name" to "Українська", "plural" to "east-slavic", "fallback" to "ru"), strings.languageDeclarations["uk"])
        assertTrue("uk" !in strings.namespaces, "a folder holding only a language declaration is no namespace")
        assertEquals("Покладено фікстурою до слів лаунчера", strings.namespaces.getValue("nexira").getValue("uk")["fixture.inLauncher"])
    }

    @Test
    fun `a language file that cannot be read costs the module that language only`() {
        install("good.jar", extra = mapOf("i18n/de.properties" to "broken=\\uZZZZ\n"))

        val module = scan().loaded.single()

        assertEquals(setOf("en", "ru"), module.strings.languages)
        assertEquals("fixture", module.id)
    }

    @Test
    fun `every kind of contribution is found in the jar, each by its own service file`() {
        install("good.jar")

        val module = scan().loaded.single()

        assertEquals(listOf("fixture.effect"), module.contributions.filterIsInstance<ScreenEffect>().map { it.id })
        assertEquals(listOf("fixture.keys"), module.contributions.filterIsInstance<KeyContribution>().map { it.id })
    }

    @Test
    fun `a kind the host did not ask for is not read`() {
        install("good.jar")

        val module = WidgetModuleLoader(modules, contributionKinds = listOf(ScreenEffect::class.java)).scan()
            .also { opened += it.loaded }.loaded.single()

        assertEquals(listOf("fixture.effect"), module.contributions.map { it.id })
    }

    @Test
    fun `a kind a module API declares is read from a jar that names the API, as the API's own interface`() {
        install("themed.jar", requires = "${ThemesApi.ID}=${ThemesApi.VERSION}")

        val scan = themesScan()

        assertEquals(emptyList(), scan.rejected)
        val theme = scan.loaded.single().contributions.filterIsInstance<ThemeContribution>().single()
        assertEquals("fixture.theme", theme.id)
        assertTrue(theme.javaClass.classLoader !== javaClass.classLoader, "the theme resolved through the parent loader")
        assertSame(ThemeContribution::class.java, theme.javaClass.interfaces.single(), "the jar implements the launcher's interface, not a copy")
    }

    @Test
    fun `a jar that brings a theme without naming the themes API cannot see it, and is refused where themes are read`() {
        install("unthemed.jar")

        val scan = themesScan()

        assertEquals(emptyList(), scan.loaded)
        val reason = scan.rejected.single().reason
        assertTrue("ThemeContribution" in reason && "name ${ThemesApi.ID} in ${WidgetApi.MANIFEST_REQUIRES}" in reason, reason)
    }

    @Test
    fun `the themes API is not shown to a jar that does not name it`() {
        install("good.jar")

        val loader = themesScan(kinds = ModuleContribution.kinds).loaded.single().loader

        assertFailsWith<ClassNotFoundException> { loader.loadClass(ThemeContribution::class.java.name) }
    }

    @Test
    fun `two jars carrying one module id load the first and refuse the second by name`() {
        install("a.jar")
        install("b.jar")

        val scan = scan()

        assertEquals(listOf("fixture"), scan.loaded.map { it.id })
        assertTrue("already carried by a.jar" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a contribution id with a slash is refused`() {
        install("good.jar")

        val scan = withProperty("fixture.effectSlashId") { scan() }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("slash" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a damaged jar is refused rather than taking the scan down with it`() {
        modules.resolve("broken.jar").writeText("this is not a zip")
        install("good.jar")

        val scan = scan()

        assertEquals(1, scan.loaded.size, "one bad file must not cost the others")
        assertEquals(1, scan.rejected.size)
    }

    @Test
    fun `a module whose registry cannot list its widgets is refused, not loaded`() {
        install("good.jar")
        System.setProperty("fixture.failListing", "1")
        val scan = try {
            scan()
        } finally {
            System.clearProperty("fixture.failListing")
        }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("list its widgets" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a module whose props class cannot be loaded is refused, not the whole scan`() {
        install("good.jar")
        System.setProperty("fixture.failProps", "1")
        val scan = try {
            scan()
        } finally {
            System.clearProperty("fixture.failProps")
        }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("props class" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a refused module leaves its jar closed`() {
        // Vacuous on a host that will unlink an open file; the point is the host
        // that will not, where a loader left behind by a refused jar makes that
        // file undeletable for the rest of the session.
        install("empty.jar", withService = false)

        assertEquals(1, scan().rejected.size)

        Files.delete(modules.resolve("empty.jar"))
    }

    @Test
    fun `files that are not jars are ignored silently`() {
        modules.resolve("notes.txt").writeText("hello")
        modules.resolve("subdir").createDirectories()

        assertEquals(WidgetModuleScan(), scan())
    }

    @Test
    fun `a missing directory is a launcher with no modules, not an error`() {
        assertEquals(WidgetModuleScan(), scan(tmp.resolve("nothing-here")))
    }

    // -- module APIs ----------------------------------------------------------

    @Test
    fun `a jar built against an older minor of an API offered here loads`() {
        install("older-minor.jar", requires = "nexira.packs=1.0")

        val scan = scan(offered = mapOf("nexira.packs" to ApiVersion(1, 3)))

        assertEquals(emptyList(), scan.rejected)
        assertEquals("fixture", scan.loaded.single().id)
    }

    @Test
    fun `a jar built against another major of an API is refused, naming both versions`() {
        install("other-major.jar", requires = "nexira.packs=2.0")

        val scan = scan(offered = mapOf("nexira.packs" to ApiVersion(1, 3)))

        assertEquals(emptyList(), scan.loaded)
        val reason = scan.rejected.single().reason
        assertTrue("2.0" in reason && "1.3" in reason, reason)
    }

    @Test
    fun `a jar built against a newer minor than the one here is refused`() {
        install("newer-minor.jar", requires = "nexira.packs=1.4")

        val scan = scan(offered = mapOf("nexira.packs" to ApiVersion(1, 3)))

        assertEquals(emptyList(), scan.loaded)
        assertTrue("1.4" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a jar built against an API nothing here offers is refused by the API's name`() {
        install("unknown-api.jar", requires = "nexira.packs=1.0, someone.else=1.0")

        val scan = scan(offered = mapOf("nexira.packs" to ApiVersion(1, 0)))

        assertEquals(emptyList(), scan.loaded)
        assertTrue("someone.else" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @Test
    fun `a requirement that is not id=major-minor is refused rather than ignored`() {
        install("garbled.jar", requires = "nexira.packs=one")

        val scan = scan(offered = mapOf("nexira.packs" to ApiVersion(1, 0)))

        assertEquals(emptyList(), scan.loaded)
        assertTrue(WidgetApi.MANIFEST_REQUIRES in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @OptIn(HostApi::class)
    @Test
    fun `a jar's key contribution is found in it and registers its keys`() {
        install("good.jar")

        val keys = scan().loaded.single().contributions.filterIsInstance<KeyContribution>().single()
        val sources = WidgetDataRegistry()
        val result = registerModuleKeys("fixture", keys, sources, WidgetCommandRegistry(), ContributedServices())

        assertTrue(result is ModuleKeysResult.Registered, "$result")
        assertTrue(keys.javaClass.classLoader !== javaClass.classLoader, "the contribution resolved through the parent loader")
        assertEquals("sample", sources.sampleOf("fixture.greeting")?.invoke())
    }

    // -- what a jar sees of the application -----------------------------------

    @Test
    fun `the kernel and the libraries shared with the launcher are the launcher's own classes`() {
        install("good.jar")

        val loader = scan().loaded.single().loader

        listOf(WidgetApi::class.java, Unit::class.java, Composer::class.java, Logger::class.java).forEach { shared ->
            assertSame(shared, loader.loadClass(shared.name), "${shared.name} is not the launcher's copy")
        }
    }

    @Test
    fun `the launcher's own classes outside the shared set are not found through a jar`() {
        install("good.jar")

        val loader = scan().loaded.single().loader

        // On this test's class path, so the launcher has it: only the jar's view hides it.
        assertSame(WidgetModuleLoader::class.java, javaClass.classLoader.loadClass(WidgetModuleLoader::class.java.name))
        assertFailsWith<ClassNotFoundException> { loader.loadClass(WidgetModuleLoader::class.java.name) }
        assertEquals(null, loader.getResource("hivens/widget/loader/WidgetModuleLoader.class"))
    }

    @Test
    fun `a class an artifact lists is shared by name, and its package beside it is not`() {
        install("good.jar")
        // A host whose list names one class of a package that holds others as well, the
        // way the design system shares its packages with the launcher's interface.
        val listed = WidgetModuleLoader::class.java.name
        val unlisted = LoadedWidgetModule::class.java.name
        val host = ListingHost(javaClass.classLoader, mapOf(SharedClassList.pathOf("fixture-artifact") to listed))

        val loader = WidgetModuleLoader(modules, parent = host, sharedArtifacts = listOf("fixture-artifact")).scan()
            .also { opened += it.loaded }.loaded.single().loader

        assertSame(WidgetModuleLoader::class.java, loader.loadClass(listed))
        assertFailsWith<ClassNotFoundException> { loader.loadClass(unlisted) }
    }

    @Test
    fun `an artifact the application carries no list for shares nothing`() {
        install("good.jar")

        val loader = WidgetModuleLoader(modules, sharedArtifacts = listOf("not-carried")).scan()
            .also { opened += it.loaded }.loaded.single().loader

        assertFailsWith<ClassNotFoundException> { loader.loadClass(WidgetModuleLoader::class.java.name) }
    }

    @Test
    fun `a jar built against an API offered without its classes is refused, not loaded to fail later`() {
        install("unshared-api.jar", requires = "nexira.packs=1.0")

        val scan = WidgetModuleLoader(modules, offered = mapOf("nexira.packs" to ApiVersion(1, 0))).scan().also { opened += it.loaded }

        assertEquals(emptyList(), scan.loaded)
        assertTrue("without sharing its classes" in scan.rejected.single().reason, scan.rejected.single().reason)
    }

    @OptIn(HostApi::class)
    @Test
    fun `a jar's code runs with its own loader as the thread's context class loader`() {
        install("good.jar")
        val module = scan().loaded.single()
        val keys = module.contributions.filterIsInstance<KeyContribution>().single()
        val sources = WidgetDataRegistry()
        val before = Thread.currentThread().contextClassLoader

        registerModuleKeys("fixture", keys, sources, WidgetCommandRegistry(), ContributedServices())
        val seen = sources.sampleOf("fixture.contextLoaders")?.invoke()

        val own = module.loader.name
        assertEquals(mapOf("load" to own, "declare" to own, "sample" to own), seen)
        assertSame(before, Thread.currentThread().contextClassLoader, "the thread's own context class loader was not put back")
    }

    // -- loading without a restart -------------------------------------------

    @Test
    fun `a module opened from a copy leaves the original free to go`() {
        install("good.jar")
        val shadow = tmp.resolve("shadow")

        val module = WidgetModuleLoader(modules, shadowDir = shadow).scan().loaded.single().also { opened += it }

        assertTrue(module.loader.urLs.single().path.startsWith(shadow.toUri().path), "it was opened from the copy")
        // The point on every host: the file the person put there is not held.
        Files.delete(modules.resolve("good.jar"))
        assertEquals("fixture", module.id)
    }

    @Test
    fun `a jar replaced while loaded is read afresh on the next scan`() {
        val shadow = tmp.resolve("shadow")
        install("mod.jar", name = "First")
        val first = WidgetModuleLoader(modules, shadowDir = shadow).scan().loaded.single().also { opened += it }

        install("mod.jar", name = "Second")
        val second = WidgetModuleLoader(modules, shadowDir = shadow).scan().loaded.single().also { opened += it }

        assertEquals("First", first.name)
        assertEquals("Second", second.name)
        assertTrue(first.loader.urLs.single() != second.loader.urLs.single(), "different bytes, different copy")
    }

    @Test
    fun `a switched-off module is named and left closed`() {
        install("good.jar")

        val scan = WidgetModuleLoader(modules, disabled = setOf("fixture")).scan().also { opened += it.loaded }

        assertEquals(emptyList(), scan.loaded)
        assertEquals(listOf("fixture"), scan.disabled.map { it.id })
        assertEquals("Fixture Module", scan.disabled.single().name)
    }

    @Test
    fun `a failure inside a module is traced back to it`() {
        install("good.jar")
        val registry = scan().loaded.single().registry

        val failure = runCatching { registry.javaClass.getMethod("explode").invoke(registry) }.exceptionOrNull()!!

        // Reflection wraps it, the way Compose wraps a widget's failure: the trace has
        // to be found through the cause.
        assertEquals("fixture", WidgetModuleLoader.moduleIdIn(failure))
    }

    @Test
    fun `a failure in the launcher's own code is no module's`() {
        assertEquals(null, WidgetModuleLoader.moduleIdIn(IllegalStateException("ours")))
    }

    // -- fixture ------------------------------------------------------------

    /** [parent] with [extra] resources on top, by path and text, the way an artifact's list would be there. */
    private class ListingHost(parent: ClassLoader, private val extra: Map<String, String>) : ClassLoader(parent) {
        override fun getResourceAsStream(name: String): InputStream? =
            extra[name]?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) } ?: super.getResourceAsStream(name)
    }

    /**
     * Copies the built fixture module into the scan directory, optionally with a
     * damaged manifest or its service entry withheld. Everything else about the
     * jar -- the compiled registry, the descriptor it carries -- is genuine.
     */
    private fun <T> withProperty(name: String, block: () -> T): T {
        System.setProperty(name, "1")
        return try {
            block()
        } finally {
            System.clearProperty(name)
        }
    }

    private fun install(
        fileName: String,
        id: String? = "fixture",
        name: String? = "Fixture Module",
        api: Int? = WidgetApi.VERSION,
        apiText: String? = null,
        withService: Boolean = true,
        withRegistryService: Boolean = true,
        extra: Map<String, String> = emptyMap(),
        requires: String? = null,
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            (apiText ?: api?.toString())?.let { mainAttributes.putValue(WidgetApi.MANIFEST_VERSION, it) }
            id?.let { mainAttributes.putValue(WidgetApi.MANIFEST_ID, it) }
            name?.let { mainAttributes.putValue(WidgetApi.MANIFEST_NAME, it) }
            requires?.let { mainAttributes.putValue(WidgetApi.MANIFEST_REQUIRES, it) }
        }
        JarFile(fixtureJar.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(modules.resolve(fileName)), manifest).use { out ->
                source.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }
                    .filterNot { !withService && it.name.startsWith("META-INF/services/") }
                    .filterNot { !withRegistryService && it.name == "META-INF/services/hivens.widget.api.WidgetRegistry" }
                    .forEach { entry ->
                        out.putNextEntry(JarEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                extra.forEach { (name, text) ->
                    out.putNextEntry(JarEntry(name))
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
            }
        }
    }
}
