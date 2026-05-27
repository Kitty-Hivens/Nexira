package hivens.launcher

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private lateinit var scope: CoroutineScope

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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmpDir.toFile().deleteRecursively()
    }

    private fun repo() = LayoutGraphRepository(file, json, scope) { sampleDefault }

    @Test
    fun `missing file -- seeds the default and writes it`() = runBlocking {
        assertFalse(Files.exists(file))
        val repo = repo()
        assertEquals(sampleDefault, repo.value())
        assertEquals(sampleDefault, repo.observe().first())
        assertTrue(Files.exists(file), "first-run load should seed the file")
        assertTrue("schema_version" in Files.readString(file))
    }

    @Test
    fun `update mutates state and survives a reload`() = runBlocking {
        val repo = repo()
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
        // Wait for the debounced write to land.
        repo.flush()

        val reloaded = LayoutGraphRepository(file, json, scope) { sampleDefault }
        val widgets = reloaded.value()
            .surfaces[SurfaceId("home.classic")]!!
            .slots[SlotId("main")]!!
            .widgets
        assertEquals(listOf(widget), widgets)
    }

    @Test
    fun `update with identity transform does not rewrite file`() = runBlocking {
        val repo = repo()
        repo.flush()  // ensure seeded write completed
        val beforeMtime = Files.getLastModifiedTime(file)
        Thread.sleep(50)
        repo.update { it }
        repo.flush()
        val afterMtime = Files.getLastModifiedTime(file)
        assertEquals(beforeMtime, afterMtime, "no-op update must not touch the file")
    }

    @Test
    fun `corrupt file -- falls back to default without crashing`() = runBlocking {
        Files.writeString(file, "{this is not valid json")
        val repo = repo()
        assertEquals(sampleDefault, repo.value())
    }

    @Test
    fun `negative schema_version is rejected without spinning the migration loop`() = runBlocking {
        // A corrupted or hand-edited envelope with Int.MIN_VALUE would
        // make `fromVersion until CURRENT` iterate billions of times
        // and hang the launcher on startup. Migrations.apply must
        // reject and let load()'s catch fall back to default.
        Files.writeString(
            file,
            """{"schema_version":-2147483648,"graph":{"surfaces":{}}}""",
        )
        val started = System.nanoTime()
        val repo = repo()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(sampleDefault, repo.value())
        assertTrue(elapsedMs < 1_000, "load must fail-fast, took ${elapsedMs}ms")
    }

    @Test
    fun `observe re-emits after update`() = runBlocking {
        val repo = repo()
        val flow = repo.observe()
        assertEquals(sampleDefault, flow.first())

        repo.update { LayoutGraph.EMPTY }
        assertEquals(LayoutGraph.EMPTY, flow.first())
    }

    @Test
    fun `persisted file uses the versioned envelope shape`() = runBlocking {
        val repo = repo()
        repo.update { LayoutGraph.EMPTY }
        repo.flush()
        val text = Files.readString(file)
        assertTrue("schema_version" in text)
        assertTrue("graph" in text)
        assertTrue("surfaces" in text)
    }

    // ── Debounce + flush ──────────────────────────────────────────────

    @Test
    fun `drag-thrash collapses to roughly one write per debounce window`() = runBlocking {
        val repo = repo()
        repo.flush()  // settle the seed write
        val beforeMtime = Files.getLastModifiedTime(file)

        // Fire 30 updates rapidly. Without debounce this would produce
        // 30 file writes; with 200ms debounce we expect 0 (still pending)
        // until we flush or wait out the window.
        repeat(30) { i ->
            repo.update { graph ->
                graph.copy(
                    surfaces = graph.surfaces + (SurfaceId("scratch-$i") to SurfaceLayout()),
                )
            }
        }

        // Immediately after, the file must NOT yet reflect the writes
        // (within 200ms debounce window, give or take scheduling slop).
        val midMtime = Files.getLastModifiedTime(file)
        assertEquals(beforeMtime, midMtime, "writes must not have landed within debounce window")

        // Flush completes the single coalesced write.
        repo.flush()
        val afterMtime = Files.getLastModifiedTime(file)
        assertTrue(afterMtime > beforeMtime, "flush must land the coalesced write on disk")
    }

    @Test
    fun `flush() persists the latest state synchronously`() = runBlocking {
        val repo = repo()
        repo.flush()  // settle seed

        val widget = WidgetInstance(WidgetKind("k"), "i-flush-test", JsonObject(emptyMap()))
        repo.update { it.copy(
            surfaces = it.surfaces + (SurfaceId("fresh") to SurfaceLayout(
                slots = mapOf(SlotId("only") to SlotContent(listOf(widget)))
            ))
        ) }
        // Immediately flush -- no waiting for debounce.
        repo.flush()

        val text = Files.readString(file)
        assertTrue("i-flush-test" in text, "flush must have written the latest state")
    }

    @Test
    fun `flush() with no pending write is a no-op and does not touch the file`() = runBlocking {
        val repo = repo()
        repo.flush()  // settle seed
        val beforeMtime = Files.getLastModifiedTime(file)
        Thread.sleep(50)
        repo.flush()  // no pending write
        val afterMtime = Files.getLastModifiedTime(file)
        assertEquals(beforeMtime, afterMtime)
    }

    @Test
    fun `debounce window eventually persists without explicit flush`() = runBlocking {
        val repo = repo()
        repo.flush()  // settle seed
        val beforeMtime = Files.getLastModifiedTime(file)
        Thread.sleep(50)

        repo.update {
            it.copy(surfaces = it.surfaces + (SurfaceId("debounce-test") to SurfaceLayout()))
        }

        // Wait past the debounce window plus dispatch slop.
        delay(400)

        val afterMtime = Files.getLastModifiedTime(file)
        assertTrue(afterMtime > beforeMtime, "debounce coroutine must have written on its own")
    }

    // ── Tree-wide uniqueness ──────────────────────────────────────────

    @Test
    fun `duplicate instanceId in tree is rejected`() = runBlocking {
        val repo = repo()
        val before = repo.value()

        repo.update { graph ->
            // Inject two widgets with the same instanceId across slots.
            val w1 = WidgetInstance(WidgetKind("a"), "dup", JsonObject(emptyMap()))
            val w2 = WidgetInstance(WidgetKind("b"), "dup", JsonObject(emptyMap()))
            graph.copy(
                surfaces = mapOf(
                    SurfaceId("s") to SurfaceLayout(slots = mapOf(
                        SlotId("a") to SlotContent(listOf(w1)),
                        SlotId("b") to SlotContent(listOf(w2)),
                    )),
                ),
            )
        }

        assertEquals(before, repo.value(), "duplicate instanceId update must be rejected")
    }

    @Test
    fun `duplicate instanceId across nesting depth is also rejected`() = runBlocking {
        val repo = repo()
        val before = repo.value()

        repo.update { _ ->
            // Container widget at root, with a child that shares the
            // container's own instanceId.
            val child = WidgetInstance(WidgetKind("child"), "same", JsonObject(emptyMap()))
            val container = WidgetInstance(
                kind       = WidgetKind("container"),
                instanceId = "same",
                children   = mapOf(SlotId("body") to SlotContent(listOf(child))),
            )
            LayoutGraph(surfaces = mapOf(
                SurfaceId("s") to SurfaceLayout(slots = mapOf(
                    SlotId("a") to SlotContent(listOf(container)),
                )),
            ))
        }

        assertEquals(before, repo.value(), "cross-depth dup must be rejected too")
    }

    // ── Schema v1 -> v2 migration ─────────────────────────────────────

    @Test
    fun `v1 envelope without children parses cleanly under v2`() = runBlocking {
        // Hand-write a v1 envelope (no `children` field on widgets).
        val widget = WidgetInstance(WidgetKind("legacy"), "i1", JsonObject(emptyMap()))
        val v1 = LayoutGraph(surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(slots = mapOf(
                SlotId("a") to SlotContent(listOf(widget)),
            )),
        ))
        Files.writeString(
            file,
            """{"schema_version":1,"graph":${json.encodeToString(LayoutGraph.serializer(), v1)}}""",
        )

        val repo = repo()
        val loaded = repo.value()
        val loadedWidget = loaded
            .surfaces[SurfaceId("s")]!!
            .slots[SlotId("a")]!!
            .widgets.first()
        assertEquals("i1", loadedWidget.instanceId)
        assertEquals(emptyMap(), loadedWidget.children)
    }

    @Test
    fun `repo can update + persist nested children at depth 1`() = runBlocking {
        val repo = repo()
        repo.flush()

        val child = WidgetInstance(WidgetKind("child"), "c1", JsonObject(emptyMap()))
        val container = WidgetInstance(
            kind       = WidgetKind("container"),
            instanceId = "ctr",
            children   = mapOf(SlotId("body") to SlotContent(listOf(child))),
        )
        repo.update { it.copy(
            surfaces = it.surfaces + (SurfaceId("with-container") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(listOf(container)))
            ))
        ) }
        repo.flush()

        val reloaded = LayoutGraphRepository(file, json, scope) { sampleDefault }
        val containerLoaded = reloaded.value()
            .surfaces[SurfaceId("with-container")]!!
            .slots[SlotId("main")]!!
            .widgets.first()
        val childLoaded = containerLoaded.children[SlotId("body")]!!.widgets.first()
        assertEquals("c1", childLoaded.instanceId)
    }

    // ── Surface reset ─────────────────────────────────────────────────

    @Test
    fun `resetSurface restores one surface from the bundled default without touching others`() = runBlocking {
        val repo = repo()
        repo.flush()

        // Mutate two surfaces: home.classic gains a widget, plus a
        // brand-new surface gets introduced.
        val extra = WidgetInstance(WidgetKind("k"), "extra-w", JsonObject(emptyMap()))
        repo.update { graph ->
            graph.copy(
                surfaces = graph.surfaces
                    .mapValues { (sid, layout) ->
                        if (sid == SurfaceId("home.classic")) {
                            layout.copy(
                                slots = layout.slots.mapValues { (_, content) ->
                                    content.copy(widgets = content.widgets + extra)
                                }
                            )
                        } else layout
                    } + (SurfaceId("scratch") to SurfaceLayout()),
            )
        }
        repo.flush()

        // Reset home.classic. Surface should match the bundled default
        // again; scratch surface must remain untouched.
        repo.resetSurface(SurfaceId("home.classic"))
        repo.flush()

        val resetLayout = repo.value().surfaces[SurfaceId("home.classic")]
        assertEquals(sampleDefault.surfaces[SurfaceId("home.classic")], resetLayout)
        assertTrue(SurfaceId("scratch") in repo.value().surfaces, "non-target surface must be left alone")
    }

    @Test
    fun `resetSurface removes a surface that is absent from the default`() = runBlocking {
        val repo = repo()
        repo.flush()

        // Introduce a surface that the bundled default does NOT have.
        repo.update { graph ->
            graph.copy(surfaces = graph.surfaces + (SurfaceId("ghost") to SurfaceLayout()))
        }
        assertTrue(SurfaceId("ghost") in repo.value().surfaces)

        repo.resetSurface(SurfaceId("ghost"))
        repo.flush()

        assertFalse(SurfaceId("ghost") in repo.value().surfaces, "absent-from-default surface should be removed on reset")
    }

    // SlotPath compile-touch: ensure tests can construct one without
    // relying on widget-model internals leaking.
    @Suppress("unused")
    private fun touchSlotPath() = SlotPath(SurfaceId("x"), SlotId("y"))

    @Suppress("unused")
    private fun touchJob(): Job? = null
}
