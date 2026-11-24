package hivens.ui.widgets.modules

import hivens.i18n.I18nEngine
import hivens.i18n.wordsOf
import hivens.test.stopThenDelete
import hivens.themes.api.ThemesApi
import hivens.ui.editor.props.words
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.WidgetApi
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.ApiVersion
import hivens.widget.model.ModuleWait
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The widget modules of a running launcher: switched off and on, read again from
 * the folder, traced from a crash, and remembered, all without a restart.
 */
class WidgetModulesTest {

    private lateinit var tmp: Path
    private lateinit var folder: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtureKind = WidgetKind("fixture.widget")

    private val fixtureJar: Path = Path.of(requireNotNull(System.getProperty("nexira.test.fixtureModuleJar")))

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("widget-modules-test-")
        folder = tmp.resolve("widgets").createDirectories()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val opened = OpenedModules()

    @AfterTest
    fun tearDown() = stopThenDelete(scope, tmp, settle = opened::closeAll)

    private fun modules() = WidgetModules(
        directory = folder,
        stateFile = tmp.resolve("widget-modules.json"),
        json      = json,
        builtIn   = GeneratedWidgetRegistry,
        scope     = scope,
        // As the launcher reads the folder: the module APIs it carries offered, and the kinds
        // of contribution their modules take read beside the kernel's.
        host      = opened.keptBy(
            DesktopWidgetModuleHost(
                directory         = folder,
                shadowDir         = tmp.resolve("shadow"),
                offered           = offeredApis(),
                apiPackages       = offeredApiPackages(),
                contributionKinds = offeredContributionKinds(),
            ),
        ),
    )

    private fun install() = fixtureJar.copyTo(folder.resolve("fixture.jar"), overwrite = true)

    @Test
    fun `a module in the folder is in the registry beside the built-in widgets`() {
        install()
        val m = modules()
        assertTrue(m.registry[fixtureKind] != null)
        assertTrue(m.registry.all().keys.containsAll(GeneratedWidgetRegistry.all().keys))
        assertTrue(m.state.value.entries.single() is WidgetModules.Entry.Loaded)
    }

    @Test
    fun `a jar dropped in while running arrives on the next read`() {
        val m = modules()
        assertNull(m.registry[fixtureKind])
        install()
        m.reload()
        assertTrue(m.registry[fixtureKind] != null, "no restart needed")
    }

    @Test
    fun `switching a module off takes its widgets out and keeps them owned`() {
        install()
        val m = modules()
        m.setEnabled("fixture", false)
        assertNull(m.registry[fixtureKind])
        assertTrue(fixtureKind in m.knownKinds(), "its kinds stay known, so the prune keeps its widgets")
        assertTrue(m.state.value.entries.single() is WidgetModules.Entry.Off)

        m.setEnabled("fixture", true)
        assertTrue(m.registry[fixtureKind] != null)
    }

    @Test
    fun `switching off survives a restart`() {
        install()
        modules().setEnabled("fixture", false)
        assertNull(modules().registry[fixtureKind])
    }

    @Test
    fun `a module whose jar is gone is remembered until forgotten`() {
        install()
        val m = modules()
        Files.delete(folder.resolve("fixture.jar"))
        m.reload()
        assertNull(m.registry[fixtureKind])
        assertEquals(WidgetModules.Entry.Gone("fixture"), m.state.value.entries.single())
        assertTrue(fixtureKind in m.knownKinds())

        m.forget("fixture")
        assertTrue(fixtureKind !in m.knownKinds(), "forgotten, its widgets may be reaped")
        assertEquals(emptyList(), m.state.value.entries)
    }

    @Test
    fun `a module's effects are listed with their scope and its words`() {
        install()
        val effect = modules().state.value.effects.single()

        assertEquals("fixture/fixture.effect", effect.scope)
        val words = modules().state.value.words.getValue(effect.moduleId)
        val english = I18nEngine(null, "en").apply { contribute(effect.moduleId, words.wordsOf(effect.moduleId)) }
        assertEquals("Fixture Effect", english.strings(effect.moduleId).text(effect.item.label))
        assertEquals(3, (modules().state.value.entries.single() as WidgetModules.Entry.Loaded).contributions, "the fixture carries an effect, a theme and its keys")
    }

    @Test
    fun `what is set for an effect is applied at once and survives a restart`() {
        install()
        val m = modules()
        m.setEffectSetting("fixture/fixture.effect", WidgetModules.EFFECT_ENABLED, "true")
        m.setEffectSetting("fixture/fixture.effect", "amount", "0.75")

        assertEquals(mapOf("enabled" to "true", "amount" to "0.75"), m.settings.value["fixture/fixture.effect"])
        assertEquals("0.75", modules().settings.value["fixture/fixture.effect"]?.get("amount"))
    }

    @Test
    fun `settings are kept for a module that is switched off`() {
        install()
        val m = modules()
        m.setEffectSetting("fixture/fixture.effect", "amount", "0.75")
        m.setEnabled("fixture", false)

        assertEquals(emptyList(), m.state.value.effects)
        assertEquals("0.75", m.settings.value["fixture/fixture.effect"]?.get("amount"))
    }

    @Test
    fun `forgetting a module forgets what was set for it`() {
        install()
        val m = modules()
        m.setEffectSetting("fixture/fixture.effect", "amount", "0.75")
        Files.delete(folder.resolve("fixture.jar"))
        m.reload()
        m.forget("fixture")

        assertEquals(emptyMap(), m.settings.value)
    }

    @Test
    fun `a crash from a module's code switches that module off and says so`() {
        install()
        val m = modules()
        val module = m.state.value.registry.all()[fixtureKind]!!
        // The fixture's registry class carries a method that fails from inside the
        // module, which is what a widget's failure looks like from the shell.
        val owner = Class.forName("fixture.FixtureRegistry", true, module.javaClass.classLoader)
        val failure = runCatching { owner.getMethod("explode").invoke(owner.getDeclaredConstructor().newInstance()) }.exceptionOrNull()!!

        assertEquals("fixture", m.culpritOf(failure))
        m.switchOffAfterCrash("fixture", failure)

        assertNull(m.registry[fixtureKind], "switched off")
        val off = m.state.value.entries.single() as WidgetModules.Entry.Off
        assertTrue(off.crash!!.isNotBlank(), "and the list says it crashed")
        assertEquals("Fixture Module", m.crashNotice.value?.name)
        m.consumeCrashNotice()
        assertNull(m.crashNotice.value)
    }

    @Test
    fun `a crash in the launcher's own code is no module's`() {
        install()
        assertNull(modules().culpritOf(IllegalStateException("ours")))
    }

    // -- modules that require each other ---------------------------------------

    /** The fixture under [id], as a module of version 1.0 that requires [requires]. */
    private fun installAs(id: String, requires: String? = null) {
        val themes = "${ThemesApi.ID}=${ThemesApi.VERSION}"
        JarFile(fixtureJar.toFile()).use { source ->
            val manifest = Manifest(source.manifest).apply {
                mainAttributes.putValue(WidgetApi.MANIFEST_ID, id)
                mainAttributes.putValue(WidgetApi.MANIFEST_NAME, "Module $id")
                mainAttributes.putValue(WidgetApi.MANIFEST_MODULE_VERSION, "1.0")
                mainAttributes.putValue(WidgetApi.MANIFEST_REQUIRES, listOfNotNull(themes, requires).joinToString(","))
            }
            JarOutputStream(Files.newOutputStream(folder.resolve("$id.jar")), manifest).use { out ->
                source.entries().asSequence().filterNot { it.isDirectory || it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }.forEach { entry ->
                    out.putNextEntry(JarEntry(entry.name))
                    source.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private inline fun <reified T : WidgetModules.Entry> WidgetModules.lines(): List<T> = state.value.entries.filterIsInstance<T>()

    @Test
    fun `a module waits for one that is not there, is not taken for gone, and keeps its widgets`() {
        installAs("base")
        installAs("app", requires = "base=1.0")
        val m = modules()
        assertEquals(listOf("base", "app"), m.lines<WidgetModules.Entry.Loaded>().map { it.id })

        Files.delete(folder.resolve("base.jar"))
        m.reload()

        val waiting = m.lines<WidgetModules.Entry.Waiting>().single()
        assertEquals("app" to "Module app", waiting.id to waiting.name)
        assertEquals(listOf<ModuleWait>(ModuleWait.Missing("base", ApiVersion(1, 0))), waiting.reasons)
        assertEquals(listOf("base"), m.lines<WidgetModules.Entry.Gone>().map { it.id })
        assertNull(m.registry[fixtureKind])
        assertTrue(fixtureKind in m.knownKinds())

        installAs("base")
        m.reload()
        assertEquals(listOf("base", "app"), m.lines<WidgetModules.Entry.Loaded>().map { it.id }, "it comes back by itself")
    }

    @Test
    fun `a module others require can be switched off, says who stops with it, and they wait until it is back`() {
        installAs("base")
        installAs("mid", requires = "base=1.0")
        installAs("top", requires = "mid=1.0")
        val m = modules()
        assertEquals(listOf("Module mid", "Module top"), m.lines<WidgetModules.Entry.Loaded>().single { it.id == "base" }.stops)
        assertEquals(emptyList(), m.lines<WidgetModules.Entry.Loaded>().single { it.id == "top" }.stops)

        m.setEnabled("base", false)

        assertEquals(emptyList(), m.lines<WidgetModules.Entry.Loaded>())
        val reasons = m.lines<WidgetModules.Entry.Waiting>().associate { it.id to it.reasons }
        assertEquals(listOf<ModuleWait>(ModuleWait.Off("base", "base")), reasons["mid"])
        assertEquals(listOf<ModuleWait>(ModuleWait.Stopped("mid", "mid")), reasons["top"])
        assertEquals("Module base", m.state.value.nameOf("base"), "the reason names it as the list does")

        m.setEnabled("base", true)
        assertEquals(listOf("base", "mid", "top"), m.lines<WidgetModules.Entry.Loaded>().map { it.id })
    }

    @Test
    fun `a module that crashed takes those that require it with it, and they are not said to have crashed`() {
        installAs("base")
        installAs("app", requires = "base=1.0")
        val m = modules()

        m.switchOffAfterCrash("base", IllegalStateException("broke"))

        assertEquals("broke", m.lines<WidgetModules.Entry.Off>().single().crash)
        assertEquals(listOf<ModuleWait>(ModuleWait.Off("base", "base")), m.lines<WidgetModules.Entry.Waiting>().single().reasons)
        assertEquals("Module base", m.crashNotice.value?.name)
    }

    @Test
    fun `a waiting module can be switched off, and is off then`() {
        installAs("app", requires = "base=1.0")
        val m = modules()
        m.setEnabled("app", false)
        assertEquals(listOf("app"), m.lines<WidgetModules.Entry.Off>().map { it.id })
        assertEquals(emptyList(), m.lines<WidgetModules.Entry.Waiting>())
    }

    @Test
    fun `what the loaded modules require is answered by name, and what a waiting one requires is not`() {
        installAs("base")
        installAs("app", requires = "base=1.0, gone=1.0")
        assertEquals(setOf(ThemesApi.ID), modules().state.value.requires)
    }

    @Test
    fun `why a module waits is said in the person's words, naming the module`() {
        val s = NexiraStrings.translator("en").strings(NexiraStrings.NAMESPACE)
        val name: (String) -> String = { "<$it>" }
        assertEquals("Needs wobble 1.0, which is not here.", words(s, ModuleWait.Missing("wobble", ApiVersion(1, 0)), name))
        assertEquals("Needs wobble 2.0, and 1.3 is here.", words(s, ModuleWait.OtherVersion("wobble", ApiVersion(2, 0), ApiVersion(1, 3), "base"), name))
        assertTrue("<base>" in words(s, ModuleWait.Off("wobble", "base"), name))
        assertTrue("<base>" in words(s, ModuleWait.Stopped("wobble", "base"), name))
        assertTrue("<a>, <b>" in words(s, ModuleWait.Cycle(listOf("a", "b")), name))
    }
}
