package hivens.ui.layout

import hivens.ui.bootstrap.RecoveryIo
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Somebody's layout file, written by the previous release, opened by this one.
 *
 * Every other test around this change takes one piece: the step in isolation, the
 * model's transforms, the pixels the renderer puts down. None of them is the
 * thing a person actually does, which is upgrade and find their screen either
 * intact or not. This drives the production read path end to end, from bytes on
 * disk through the structural step, the decoder, the semantic ladder, the merge
 * against the bundled default and the uniqueness sweep, and then checks what the
 * write-back leaves behind.
 */
class LayoutUpgradeTest {

    private lateinit var tmpDir: Path
    private lateinit var file: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @BeforeTest
    fun setUp() {
        RecoveryIo.resetForTests()
        tmpDir = Files.createTempDirectory("layout-upgrade-test")
        tmpDir.toFile().deleteOnExit()
        file = tmpDir.resolve("layout-graph.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Files.walk(tmpDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** The bundled default this test pretends the build ships, kept small on purpose. */
    private val bundled = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = emptyList())),
            ),
        ),
    )

    private fun repo() = LayoutGraphRepository(file, json, scope) { bundled }

    /**
     * A file in the shape the previous release wrote: one of every slot mode, and
     * a widget in each carrying the field that mode read.
     */
    private val savedAtSchema9 = """
        {
          "schema_version": 9,
          "graph": {
            "surfaces": {
              "home.new": {
                "slots": {
                  "main": {
                    "orientation": "Row",
                    "widgets": [
                      {"kind":"a","instance_id":"weighted","weight":2.0},
                      {"kind":"b","instance_id":"bounded","canvas":{"x":0.0,"y":0.0,"width":300.0,"height":0.0,"z":0}}
                    ]
                  },
                  "free": {
                    "orientation": "Canvas",
                    "widgets": [
                      {"kind":"c","instance_id":"placed","canvas":{"x":120.0,"y":40.0,"width":200.0,"height":150.0,"z":4}}
                    ]
                  },
                  "cells": {
                    "orientation": "CubeGrid",
                    "gridColumns": 6,
                    "widgets": [
                      {"kind":"d","instance_id":"celled","cell":{"col":2,"row":1,"colSpan":3,"rowSpan":2,"z":1}}
                    ]
                  },
                  "stacked": {
                    "widgets": [
                      {"kind":"e","instance_id":"plain","props":{"title":"kept"}}
                    ]
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun loadSaved(): LayoutGraph {
        Files.writeString(file, savedAtSchema9)
        return repo().value()
    }

    private fun slot(g: LayoutGraph, id: String) = g.surfaces[SurfaceId("home.new")]!!.slots[SlotId(id)]!!

    @Test
    fun `a row stays a row and its weighted widget keeps its share`() {
        val main = slot(loadSaved(), "main")
        assertEquals(true, main.flow?.horizontal, "the row came back as a column")
        assertEquals(2f, main.widgets.first { it.instanceId == "weighted" }.placement?.weight)
    }

    @Test
    fun `a resized widget in a flow keeps its bound`() {
        val bounded = slot(loadSaved(), "main").widgets.first { it.instanceId == "bounded" }
        assertEquals(300f, bounded.placement?.width)
        assertEquals(0f, bounded.placement?.height, "an unset axis stays unbounded")
    }

    @Test
    fun `a free canvas stays free and its widget keeps offset, size and layer`() {
        val free = slot(loadSaved(), "free")
        assertNull(free.flow, "the canvas came back as a flow")
        assertEquals(0, free.grid, "a free slot measures in dp")
        val p = assertNotNull(free.widgets.single().placement)
        assertEquals(120f, p.x)
        assertEquals(40f, p.y)
        assertEquals(200f, p.width)
        assertEquals(150f, p.height)
        assertEquals(4, p.z)
    }

    @Test
    fun `a cell grid keeps its lattice, and its widget keeps its cell and span`() {
        val cells = slot(loadSaved(), "cells")
        assertNull(cells.flow)
        assertEquals(6, cells.grid, "the column count was the lattice")
        val p = assertNotNull(cells.widgets.single().placement)
        assertEquals(2f, p.x)
        assertEquals(1f, p.y)
        assertEquals(3f, p.width)
        assertEquals(2f, p.height)
        assertEquals(1, p.z)
    }

    @Test
    fun `a slot that named no orientation comes back a column, with its props`() {
        val stacked = slot(loadSaved(), "stacked")
        assertEquals(false, stacked.flow?.horizontal)
        assertEquals(0, stacked.flow?.wrap)
        assertEquals(
            "kept",
            stacked.widgets.single().props["title"]?.jsonPrimitive?.content,
            "props are not this step's to touch",
        )
    }

    @Test
    fun `nothing is lost and no id is minted`() {
        val loaded = loadSaved()
        val ids = loaded.surfaces.values.flatMap { it.slots.values }.flatMap { s -> s.widgets.map { it.instanceId } }
        assertEquals(setOf("weighted", "bounded", "placed", "celled", "plain"), ids.toSet())
        assertEquals(ids.size, ids.toSet().size, "the uniqueness sweep would have refused a duplicate")
    }

    @Test
    fun `the first write leaves the file at the current schema in the new shape`() = runBlocking {
        Files.writeString(file, savedAtSchema9)
        val repo = repo()
        // An identity transform is a no-op by contract and writes nothing, so the
        // file only moves when something really changes.
        repo.update { g -> g.copy(surfaces = g.surfaces + (SurfaceId("extra") to SurfaceLayout())) }
        repo.flush()

        val written = json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(LayoutReconcile.CURRENT_SCHEMA, written["schema_version"]!!.jsonPrimitive.int)

        val text = Files.readString(file)
        assertTrue(""""orientation"""" !in text, "the retired key was written back out")
        assertTrue(""""gridColumns"""" !in text, "the retired key was written back out")
        assertTrue(""""cell"""" !in text, "the retired key was written back out")
        assertTrue(""""placement"""" in text, "the new record never reached the file")
    }

    @Test
    fun `a file from before the surface schema is left alone and the default is served`() {
        // The one reset the format admits to. What matters is that the file stays
        // where it is, so a build that still understands it can be gone back to.
        val ancient = savedAtSchema9.replace("\"schema_version\": 9", "\"schema_version\": 7")
        Files.writeString(file, ancient)
        val loaded = repo().value()
        assertEquals(bundled, loaded, "a graph with no faithful reading must not be half-read")
        assertEquals(ancient, Files.readString(file), "the file was overwritten instead of left")
    }
}
