package hivens.ui.widgets.modules

import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
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

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Files.walk(tmp).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun modules() = WidgetModules(
        directory = folder,
        shadowDir = tmp.resolve("shadow"),
        stateFile = tmp.resolve("widget-modules.json"),
        json      = json,
        builtIn   = GeneratedWidgetRegistry,
        scope     = scope,
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
}
