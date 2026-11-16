package hivens.ui.layout

import hivens.test.stopThenDelete
import hivens.ui.customization.CustomizationSettings
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.modulesNamedBy
import hivens.widget.model.DefaultLayout
import hivens.widget.model.LayoutGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The standard module list is read from the build at every start and never frozen
 * into a file: not by the first start, not by a reset, not by a preset saved or
 * loaded. So a launcher update that brings a module in brings it to everybody who
 * did not choose a list by hand, and a list chosen by hand is kept as it is.
 */
class ModuleListPersistenceTest {

    private val dir: Path = Files.createTempDirectory("module-list-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = dir.resolve("layout-graph.json")

    /** The standard list of the build being "started", which a test can grow. */
    private var standard = listOf("nexira.packs")

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    private fun start() = LayoutGraphRepository(file, json, scope, defaultGraph = { DefaultLayout.load(json) }, standardModules = { standard })

    /** The module list as the file has it: null where the file names none. */
    private fun onDisk(): List<String>? {
        val graph = json.parseToJsonElement(file.readText()).jsonObject["graph"]?.jsonObject ?: return null
        val modules = graph["modules"] ?: return null
        if (modules is JsonNull) return null
        return json.decodeFromJsonElement(ListSerializer(String.serializer()), modules)
    }

    @Test
    fun `the first start writes the bundled arrangement without the module list`() {
        val repo = start()
        assertNull(repo.value().modules)
        assertNull(onDisk(), "the standard list went into the file")
        assertEquals(setOf("nexira.packs"), modulesNamedBy(repo.value()) { standard })
    }

    @Test
    fun `a build whose standard list grew brings the new module to a file written before`() {
        start()
        standard = listOf("nexira.packs", "nexira.accounts")
        assertEquals(setOf("nexira.packs", "nexira.accounts"), modulesNamedBy(start().value()) { standard })
    }

    @Test
    fun `a graph naming exactly the standard list is kept and written as naming none`() = runBlocking {
        val repo = start()
        repo.update { it.copy(modules = listOf("nexira.packs")) }
        repo.flush()
        assertNull(repo.value().modules)
        assertNull(onDisk())

        // A file that carries the standard list, written by hand or by an older build.
        file.writeText(file.readText().replace("\"modules\": null", "\"modules\": [\"nexira.packs\"]"))
        assertNull(start().value().modules, "read as the standard interface it is")
    }

    @Test
    fun `a list chosen by hand is kept, the empty one included, and a reset goes back to the standard one`() = runBlocking {
        val repo = start()
        repo.update { it.copy(modules = emptyList()) }
        repo.flush()
        assertEquals(emptyList(), onDisk())
        assertEquals(emptyList(), start().value().modules, "a hand-chosen list survives a start")

        repo.resetAll()
        repo.flush()
        assertNull(repo.value().modules)
        assertNull(onDisk())
    }

    @Test
    fun `a preset neither saves the module list nor brings one in when loaded`() = runBlocking {
        val presets = PresetRepository(dir.resolve("presets"), json)
        presets.save("mine", LayoutGraph(modules = emptyList()), CustomizationSettings())
        val saved = presets.load("mine")!!.graph
        assertNull(saved.modules, "a saved arrangement carried a module list")
        val written = Files.list(dir.resolve("presets")).use { it.toList().single() }.readText()
        assertTrue("\"modules\": []" !in written, "the list was written into the preset")

        val repo = start()
        repo.update { it.copy(modules = listOf("nexira.packs", "my.own")) }
        val editor = EditModeController(repo, scope)
        editor.loadArrangement(saved.copy(modules = emptyList()))
        editor.awaitWrites()
        assertEquals(listOf("nexira.packs", "my.own"), repo.value().modules, "loading an arrangement changed which modules come in")
    }
}
