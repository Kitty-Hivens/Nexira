package hivens.launcher

import hivens.widget.model.CanvasPlacement
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetChrome
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
    fun `newer schema_version loads read-only and is never written back`() = runBlocking {
        // A future build bumped the layout schema. An older binary that opens
        // the file must read it best-effort but never overwrite it -- otherwise
        // it downgrades the envelope and discards layout it can't represent.
        val newer = """{"schema_version":99,"graph":{"surfaces":{}}}"""
        Files.writeString(file, newer)
        val before = Files.readString(file)

        val repo = repo()
        repo.update { LayoutGraph.EMPTY }   // in-memory mutation still applies
        repo.flush()

        assertEquals(LayoutGraph.EMPTY, repo.value(), "in-memory state still mutates under read-only")
        assertEquals(before, Files.readString(file), "an older build must not overwrite a newer-schema file")
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
    fun `v2 envelope without chrome parses cleanly under v3`() = runBlocking {
        // A v2 file pre-dates the chrome field; it must load with null chrome
        // (the v2 -> v3 migration is identity, deserialization fills the default).
        val widget = WidgetInstance(WidgetKind("legacy"), "i1", JsonObject(emptyMap()))
        val v2 = LayoutGraph(surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(slots = mapOf(
                SlotId("a") to SlotContent(listOf(widget)),
            )),
        ))
        Files.writeString(
            file,
            """{"schema_version":2,"graph":${json.encodeToString(LayoutGraph.serializer(), v2)}}""",
        )

        val loaded = repo().value()
            .surfaces[SurfaceId("s")]!!.slots[SlotId("a")]!!.widgets.first()
        assertEquals("i1", loaded.instanceId)
        assertEquals(null, loaded.chrome)
    }

    @Test
    fun `widget chrome survives a write + reload`() = runBlocking {
        val repo = repo()
        repo.flush() // settle seed
        val widget = WidgetInstance(
            kind = WidgetKind("k"),
            instanceId = "chrome-1",
            props = JsonObject(emptyMap()),
            chrome = WidgetChrome(glassAlphaPct = 45, cornerRadiusDp = 10, paddingDp = 4),
        )
        repo.update {
            it.copy(surfaces = it.surfaces + (SurfaceId("cx") to SurfaceLayout(
                slots = mapOf(SlotId("o") to SlotContent(listOf(widget))),
            )))
        }
        repo.flush()

        val reloaded = LayoutGraphRepository(file, json, scope) { sampleDefault }
        val w = reloaded.value().surfaces[SurfaceId("cx")]!!.slots[SlotId("o")]!!.widgets.first()
        assertEquals(WidgetChrome(glassAlphaPct = 45, cornerRadiusDp = 10, paddingDp = 4), w.chrome)
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

    // ── Schema v3 -> v4 nav migration ─────────────────────────────────

    private fun loadFrom(version: Int, graph: LayoutGraph, default: LayoutGraph = sampleDefault): LayoutGraph {
        Files.writeString(
            file,
            """{"schema_version":$version,"graph":${json.encodeToString(LayoutGraph.serializer(), graph)}}""",
        )
        return LayoutGraphRepository(file, json, scope) { default }.value()
    }

    private fun leftrailGraph(top: List<WidgetInstance>, bottom: List<WidgetInstance> = emptyList()) =
        LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.leftrail") to SurfaceLayout(slots = mapOf(
                SlotId("top")    to SlotContent(top),
                SlotId("bottom") to SlotContent(bottom),
            )),
        ))

    private fun navKind(kind: String, id: String, chrome: WidgetChrome? = null, weight: Float = 0f) =
        WidgetInstance(WidgetKind(kind), id, JsonObject(emptyMap()), chrome = chrome, weight = weight)

    private fun LayoutGraph.leftrailSlot(slot: String) =
        surfaces[SurfaceId("appshell.leftrail")]!!.slots[SlotId(slot)]!!.widgets

    private fun WidgetInstance.target() = props["target"]?.jsonPrimitive?.content

    @Test
    fun `v3 -- navbuttons expands to six nav-entry in declared order, siblings preserved`() {
        val v3 = leftrailGraph(
            top = listOf(navKind("appshell.leftrail.navbuttons", "nb"), navKind("home.new.spacer", "sp")),
        )
        val top = loadFrom(3, v3).leftrailSlot("top")
        assertEquals(7, top.size, "six nav entries plus the preserved sibling")
        assertEquals(List(6) { "nav.entry" } + "home.new.spacer", top.map { it.kind.value })
        assertEquals(
            listOf("Home", "Library", "Browse", "Profile", "Settings", "About"),
            top.take(6).map { it.target() },
        )
        assertEquals("sp", top[6].instanceId)
    }

    @Test
    fun `v3 -- console and logout become nav-entry keeping their ids`() {
        val v3 = leftrailGraph(
            top = listOf(navKind("appshell.leftrail.navbuttons", "nb")),
            bottom = listOf(
                navKind("appshell.leftrail.consoletoggle", "console-id"),
                navKind("appshell.leftrail.logout", "logout-id"),
            ),
        )
        val bottom = loadFrom(3, v3).leftrailSlot("bottom")
        assertEquals(
            listOf("console-id" to "Console", "logout-id" to "Logout"),
            bottom.map { it.instanceId to it.target() },
        )
        bottom.forEach { assertEquals("nav.entry", it.kind.value) }
    }

    @Test
    fun `v3 -- a stray nav widget on any surface is converted in place`() {
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(
                SlotId("main") to SlotContent(listOf(navKind("nav.home", "stray"))),
            )),
        ))
        val w = loadFrom(3, v3).surfaces[SurfaceId("home.new")]!!.slots[SlotId("main")]!!.widgets.first()
        assertEquals("nav.entry", w.kind.value)
        assertEquals("Home", w.target())
        assertEquals("stray", w.instanceId)
    }

    @Test
    fun `v4 graph passes through the nav migration untouched`() {
        val entry = WidgetInstance(
            WidgetKind("nav.entry"), "e1", JsonObject(mapOf("target" to JsonPrimitive("Home"))),
        )
        val top = loadFrom(4, leftrailGraph(top = listOf(entry))).leftrailSlot("top")
        assertEquals(listOf(entry), top)
    }

    @Test
    fun `v3 -- chrome on a non-nav widget survives the migration`() {
        val styled = WidgetInstance(
            WidgetKind("home.new.clock"), "clock", JsonObject(emptyMap()),
            chrome = WidgetChrome(glassAlphaPct = 30),
        )
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(listOf(styled)))),
        ))
        val w = loadFrom(3, v3).surfaces[SurfaceId("home.new")]!!.slots[SlotId("main")]!!.widgets.first()
        assertEquals(styled, w)
    }

    @Test
    fun `v3 -- 1to1 nav conversion preserves chrome and weight`() {
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(listOf(
                navKind("nav.profile", "p", chrome = WidgetChrome(cornerRadiusDp = 8), weight = 2f),
            )))),
        ))
        val w = loadFrom(3, v3).surfaces[SurfaceId("home.new")]!!.slots[SlotId("main")]!!.widgets.first()
        assertEquals("nav.entry", w.kind.value)
        assertEquals("Profile", w.target())
        assertEquals(WidgetChrome(cornerRadiusDp = 8), w.chrome)
        assertEquals(2f, w.weight)
    }

    @Test
    fun `v3 -- navbuttons expansion drops block chrome, weight, and canvas`() {
        val v3 = leftrailGraph(top = listOf(
            WidgetInstance(
                WidgetKind("appshell.leftrail.navbuttons"), "nb", JsonObject(emptyMap()),
                chrome = WidgetChrome(glassAlphaPct = 50),
                weight = 3f,
                canvas = CanvasPlacement(x = 10f, y = 20f, z = 5),
            ),
        ))
        val top = loadFrom(3, v3).leftrailSlot("top")
        assertEquals(6, top.size)
        // The monolith's single block frame cannot map onto six items, so the
        // expansion intentionally resets these to defaults.
        top.forEach {
            assertEquals(null, it.chrome)
            assertEquals(0f, it.weight)
            assertEquals(null, it.canvas)
        }
    }

    @Test
    fun `v3 -- migrated graph has unique instance ids tree-wide`() {
        val v3 = leftrailGraph(
            top = listOf(navKind("appshell.leftrail.navbuttons", "nb")),
            bottom = listOf(
                navKind("appshell.leftrail.consoletoggle", "c"),
                navKind("appshell.leftrail.logout", "l"),
            ),
        )
        val loaded = loadFrom(3, v3)
        val ids = loaded.surfaces.values.flatMap { it.slots.values }.flatMap { it.widgets }.map { it.instanceId }
        assertEquals(ids.toSet().size, ids.size, "migration must not produce duplicate ids")
    }

    @Test
    fun `v3 -- user without the leftrail surface gets the bundled default seeded`() {
        val defaultWithRail = leftrailGraph(top = listOf(
            WidgetInstance(WidgetKind("nav.entry"), "d-home", JsonObject(mapOf("target" to JsonPrimitive("Home")))),
        ))
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(emptyList()))),
        ))
        val loaded = loadFrom(3, v3, default = defaultWithRail)
        assertEquals(
            defaultWithRail.surfaces[SurfaceId("appshell.leftrail")],
            loaded.surfaces[SurfaceId("appshell.leftrail")],
        )
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

    @Test
    fun `bundled-default surface added in a later release auto-seeds into user graph`() = runBlocking {
        // Persist a graph that pre-dates the surface getting added to
        // default-layout in the next release.
        val priorDefault = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("home.classic") to SurfaceLayout(
                    slots = mapOf(SlotId("main") to SlotContent(widgets = emptyList())),
                ),
            ),
        )
        val priorRepo = LayoutGraphRepository(file, json, scope) { priorDefault }
        priorRepo.flush()
        assertTrue(Files.exists(file))

        // New release adds a `about` surface to the bundled default.
        val nextDefault = LayoutGraph(
            surfaces = priorDefault.surfaces + (
                SurfaceId("about") to SurfaceLayout(
                    slots = mapOf(
                        SlotId("left")  to SlotContent(widgets = emptyList()),
                        SlotId("right") to SlotContent(widgets = emptyList()),
                    ),
                )
            ),
        )
        val nextRepo = LayoutGraphRepository(file, json, scope) { nextDefault }
        val loaded = nextRepo.value()

        assertTrue(SurfaceId("about") in loaded.surfaces, "new bundled-default surface must auto-seed on load")
        assertEquals(
            nextDefault.surfaces[SurfaceId("about")],
            loaded.surfaces[SurfaceId("about")],
            "seeded surface must match the bundled default",
        )
        // Existing user data on prior surface is preserved untouched.
        assertEquals(
            priorDefault.surfaces[SurfaceId("home.classic")],
            loaded.surfaces[SurfaceId("home.classic")],
            "pre-existing surface in user graph must not be re-seeded over",
        )
    }

    @Test
    fun `bundled-default slot added to an existing surface auto-seeds into the user graph`() = runBlocking {
        // Persist a graph whose `profile` surface pre-dates a slot the
        // next release adds to that same surface.
        val priorDefault = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("profile") to SurfaceLayout(
                    slots = mapOf(
                        SlotId("nav")     to SlotContent(listOf(WidgetInstance(WidgetKind("profile.nav"), "nav-1"))),
                        SlotId("account") to SlotContent(listOf(WidgetInstance(WidgetKind("profile.account"), "acct-1"))),
                    ),
                ),
            ),
        )
        val priorRepo = LayoutGraphRepository(file, json, scope) { priorDefault }
        priorRepo.flush()
        assertTrue(Files.exists(file))

        // New release adds a `signin` slot to the existing `profile` surface.
        val signin = WidgetInstance(WidgetKind("profile.signin"), "signin-default")
        val nextDefault = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("profile") to SurfaceLayout(
                    slots = priorDefault.surfaces[SurfaceId("profile")]!!.slots +
                        (SlotId("signin") to SlotContent(listOf(signin))),
                ),
            ),
        )
        val loaded = LayoutGraphRepository(file, json, scope) { nextDefault }.value()
        val profile = loaded.surfaces[SurfaceId("profile")]!!

        assertTrue(SlotId("signin") in profile.slots, "new bundled-default slot must auto-seed into the existing surface")
        assertEquals(
            listOf(signin),
            profile.slots[SlotId("signin")]!!.widgets,
            "seeded slot must match the bundled default",
        )
        // The user's pre-existing slots in the same surface are untouched.
        assertEquals("nav-1",  profile.slots[SlotId("nav")]!!.widgets.single().instanceId)
        assertEquals("acct-1", profile.slots[SlotId("account")]!!.widgets.single().instanceId)
    }

    @Test
    fun `a slot the user already has is not re-seeded over with the default`() = runBlocking {
        // The user has reordered/edited the `nav` slot. A later default
        // for that same slot must NOT clobber the user's version.
        val userNav = WidgetInstance(WidgetKind("profile.nav"), "user-edited-nav")
        val priorDefault = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("profile") to SurfaceLayout(slots = mapOf(SlotId("nav") to SlotContent(listOf(userNav)))),
            ),
        )
        LayoutGraphRepository(file, json, scope) { priorDefault }.flush()

        val nextDefault = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("profile") to SurfaceLayout(
                    slots = mapOf(SlotId("nav") to SlotContent(listOf(WidgetInstance(WidgetKind("profile.nav"), "fresh-default-nav")))),
                ),
            ),
        )
        val loaded = LayoutGraphRepository(file, json, scope) { nextDefault }.value()
        assertEquals(
            "user-edited-nav",
            loaded.surfaces[SurfaceId("profile")]!!.slots[SlotId("nav")]!!.widgets.single().instanceId,
            "an existing slot keeps the user's content; only MISSING slots seed",
        )
    }

    // SlotPath compile-touch: ensure tests can construct one without
    // relying on widget-model internals leaking.
    @Suppress("unused")
    private fun touchSlotPath() = SlotPath(SurfaceId("x"), SlotId("y"))

    @Suppress("unused")
    private fun touchJob(): Job? = null
}
