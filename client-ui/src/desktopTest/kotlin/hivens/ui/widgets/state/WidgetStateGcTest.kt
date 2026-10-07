package hivens.ui.widgets.state

import hivens.ui.bootstrap.RecoveryIo
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.layout.LayoutReconcile
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class WidgetStateGcTest {

    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val note = JsonObject(mapOf("body" to JsonPrimitive("groceries")))

    @BeforeTest
    fun setUp() {
        RecoveryIo.resetForTests()
        dir = Files.createTempDirectory("widget-state-gc-test")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
        RecoveryIo.resetForTests()
    }

    private fun layoutAt(version: Int) {
        val theirs = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(
                SlotId("main") to SlotContent(listOf(WidgetInstance(WidgetKind("note"), "note-1"))),
            )),
        ))
        Files.writeString(
            dir.resolve("layout-graph.json"),
            """{"schema_version":$version,"graph":${json.encodeToString(LayoutGraph.serializer(), theirs)}}""",
        )
    }

    private fun sweep(): WidgetStateStore = runBlocking {
        val repo = LayoutGraphRepository(dir.resolve("layout-graph.json"), json, scope) { LayoutGraph.EMPTY }
        val store = WidgetStateStore(dir.resolve("widget-state.json"), json, scope)
        store.store("note-1", note)
        WidgetStateGc(repo, store, scope)
        delay(1_600)
        store
    }

    @Test
    fun `a layout this build will not read keeps the state of every widget in it`() {
        layoutAt(LayoutReconcile.SURFACE_SCHEMA - 1)

        assertEquals(note, sweep().load("note-1"), "the note is empty after going back to the build that reads it")
    }

    @Test
    fun `a layout read as it is still has its orphans swept`() {
        Files.writeString(
            dir.resolve("layout-graph.json"),
            """{"schema_version":${LayoutReconcile.CURRENT_SCHEMA},"graph":{"surfaces":{}}}""",
        )

        assertNull(sweep().load("note-1"))
    }
}
