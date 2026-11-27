package hivens.ui.widgets.state

import hivens.core.data.NewerBuildData
import hivens.test.stopThenDelete
import hivens.ui.bootstrap.RecoveryIo
import hivens.ui.layout.DocumentStore
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.LayoutId
import hivens.widget.model.SlotContent
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The state of a widget lives as long as the document drawn holds it, or something can still bring it back. */
class WidgetStateGcTest {

    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val note = JsonObject(mapOf("body" to JsonPrimitive("groceries")))
    private val launcher = CompositionName("launcher")

    @BeforeTest
    fun setUp() {
        RecoveryIo.resetForTests()
        NewerBuildData.reset()
        dir = Files.createTempDirectory("widget-state-gc-test")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        stopThenDelete(scope, dir)
        RecoveryIo.resetForTests()
        NewerBuildData.reset()
    }

    private fun holding(vararg ids: String): Document {
        val root = SlotContent(ids.map { WidgetInstance(WidgetKind("note"), it) })
        val home = Config(layouts = listOf(ConfigLayout(LayoutId("main"), root)))
        return Document(start = launcher, compositions = mapOf(launcher to Composition(start = ConfigId("home"), configs = mapOf(ConfigId("home") to home))))
    }

    /** A store holding notes for [ids], swept against [documents] after the debounce. */
    private fun sweep(documents: DocumentStore, vararg ids: String, also: Set<String> = emptySet()): WidgetStateStore = runBlocking {
        val store = WidgetStateStore(dir.resolve("widget-state.json"), json, scope)
        ids.forEach { store.store(it, note) }
        WidgetStateGc(documents.document, { documents.writable }, store, scope) { also }
        delay(1_600)
        store
    }

    @Test
    fun `a widget the document holds keeps its state, whatever any layout graph says`() {
        val documents = DocumentStore(dir.resolve("interface.json"), { holding("note-1", "notes#a1b2c3d4") })
        val store = sweep(documents, "note-1", "notes#a1b2c3d4", "gone")
        assertEquals(note, store.load("note-1"))
        assertEquals(note, store.load("notes#a1b2c3d4"), "a widget dropped from the palette is in no graph")
        assertNull(store.load("gone"))
    }

    @Test
    fun `what something can still bring back keeps its state`() {
        val documents = DocumentStore(dir.resolve("interface.json"), { holding("note-1") })
        val store = sweep(documents, "note-1", "undone", also = setOf("undone"))
        assertEquals(note, store.load("undone"))
    }

    @Test
    fun `a document this build will not write keeps the state of every widget`() {
        Files.writeString(dir.resolve("interface.json"), """{"schema": 99, "start": "launcher"}""")
        val documents = DocumentStore(dir.resolve("interface.json"), { holding() })
        val store = sweep(documents, "note-1")
        assertEquals(note, store.load("note-1"), "the note is empty after going back to the build that reads it")
    }

    @Test
    fun `a whole document of one's own is swept like any other`() {
        Files.writeString(dir.resolve("interface.json"), DocumentCodec.write(holding("mine")))
        val documents = DocumentStore(dir.resolve("interface.json"), { holding() })
        val store = sweep(documents, "mine", "theirs")
        assertEquals(note, store.load("mine"))
        assertNull(store.load("theirs"))
    }
}
