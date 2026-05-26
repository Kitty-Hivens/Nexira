package hivens.launcher

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutGraphRepositoryTest {

    private lateinit var tmpDir: Path
    private lateinit var file: Path
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val sampleDefault = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("home.classic") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = emptyList()))
            )
        )
    )

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("layout-repo-test")
        tmpDir.toFile().deleteOnExit()
        file = tmpDir.resolve("layout-graph.json")
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing file -- seeds the default and writes it`() = runBlocking {
        assertFalse(Files.exists(file))
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        assertEquals(sampleDefault, repo.value())
        assertEquals(sampleDefault, repo.observe().first())
        assertTrue(Files.exists(file), "first-run load should seed the file")
        assertTrue("schema_version" in Files.readString(file))
    }

    @Test
    fun `update mutates state and survives a reload`() = runBlocking {
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        val widget = WidgetInstance(
            kind = WidgetKind("home.classic.header"),
            instanceId = "i1",
            props = JsonObject(emptyMap()),
        )
        repo.update { graph ->
            graph.copy(
                surfaces = graph.surfaces.mapValues { (_, layout) ->
                    layout.copy(
                        slots = layout.slots.mapValues { (_, content) ->
                            content.copy(widgets = content.widgets + widget)
                        }
                    )
                }
            )
        }

        val reloaded = LayoutGraphRepository(file, json) { sampleDefault }
        val widgets = reloaded.value()
            .surfaces[SurfaceId("home.classic")]!!
            .slots[SlotId("main")]!!
            .widgets
        assertEquals(listOf(widget), widgets)
    }

    @Test
    fun `update with identity transform does not rewrite file`() = runBlocking {
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        val beforeMtime = Files.getLastModifiedTime(file)
        // Sleep beyond filesystem mtime granularity (2s covers FAT32/HFS+
        // worst case) so a rewrite would be detectable.
        Thread.sleep(50)
        repo.update { it }
        val afterMtime = Files.getLastModifiedTime(file)
        assertEquals(beforeMtime, afterMtime, "no-op update must not touch the file")
    }

    @Test
    fun `corrupt file -- falls back to default without crashing`() = runBlocking {
        Files.writeString(file, "{this is not valid json")
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        assertEquals(sampleDefault, repo.value())
    }

    @Test
    fun `observe re-emits after update`() = runBlocking {
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        val flow = repo.observe()
        assertEquals(sampleDefault, flow.first())

        repo.update { LayoutGraph.EMPTY }
        assertEquals(LayoutGraph.EMPTY, flow.first())
    }

    @Test
    fun `persisted file uses the versioned envelope shape`() = runBlocking {
        val repo = LayoutGraphRepository(file, json) { sampleDefault }
        repo.update { LayoutGraph.EMPTY }
        val text = Files.readString(file)
        assertTrue("schema_version" in text)
        assertTrue("graph" in text)
        assertTrue("surfaces" in text)
    }
}
