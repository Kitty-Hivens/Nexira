package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A screen somebody made is a record beside the surfaces and a surface of its
 * own. These are the rules that keep the two together: one cannot exist without
 * the other, an id names one thing, and the moves that replace a graph wholesale
 * do not take a made screen with them.
 */
class ScreensTest {

    private fun spec(id: String, title: String = "") = ScreenSpec(id, title, surface = SurfaceId("screen.$id"))

    private val home = SurfaceId("home.new")
    private val base = LayoutGraph(surfaces = mapOf(home to blankScreenSurface()))

    @Test
    fun `a new screen arrives with a blank static page`() {
        val g = base.addScreen(spec("a", "Mine"))
        assertEquals("Mine", g.screen("a")?.title)
        val main = assertNotNull(g.traverse(SlotPath(SurfaceId("screen.a"), SCREEN_MAIN_SLOT)))
        assertTrue(main.widgets.isEmpty())
        assertEquals(ViewportMode.Static, main.viewportMode, "a page does not scroll until somebody says so")
    }

    @Test
    fun `an id or a surface already taken is refused`() {
        val g = base.addScreen(spec("a"))
        assertSame(g, g.addScreen(spec("a", "again")), "the same id twice")
        assertSame(g, g.addScreen(ScreenSpec("b", surface = home)), "a screen over somebody's existing surface")
    }

    @Test
    fun `a rename keeps the id and the surface whatever the edit says`() {
        val g = base.addScreen(spec("a")).updateScreen("a") { it.copy(id = "z", title = "New", surface = home) }
        val s = assertNotNull(g.screen("a"))
        assertEquals("New", s.title)
        assertEquals(SurfaceId("screen.a"), s.surface)
    }

    @Test
    fun `deleting a screen takes its page with it`() {
        val g = base.addScreen(spec("a")).removeScreen("a")
        assertNull(g.screen("a"))
        assertTrue(SurfaceId("screen.a") !in g.surfaces)
    }

    @Test
    fun `resetting a made screen leaves it blank and still there`() {
        val filled = base.addScreen(spec("a")).insertWidget(
            SlotPath(SurfaceId("screen.a"), SCREEN_MAIN_SLOT),
            WidgetInstance(WidgetKind("k"), "w"),
            0,
        )
        val reset = filled.resetScreenSurface(SurfaceId("screen.a"))
        assertNotNull(reset.screen("a"))
        assertEquals(blankScreenSurface(), reset.surfaces[SurfaceId("screen.a")])
    }

    @Test
    fun `a graph replaced wholesale keeps the screens it did not have`() {
        val mine = base.addScreen(spec("a", "Mine")).addScreen(spec("b", "Also"))
        val default = LayoutGraph(surfaces = mapOf(home to blankScreenSurface()))
        val out = default.withScreensFrom(mine)
        assertEquals(listOf("a", "b"), out.screens.map { it.id })
        assertTrue(SurfaceId("screen.a") in out.surfaces && SurfaceId("screen.b") in out.surfaces)
        assertSame(out, out.withScreensFrom(mine), "and a second pass changes nothing")
    }

    @Test
    fun `a hand-edited list is repaired, not trusted`() {
        val broken = LayoutGraph(
            surfaces = mapOf(home to blankScreenSurface()),
            screens = listOf(spec("a", "first"), spec("a", "second"), ScreenSpec("", surface = SurfaceId("x"))),
        )
        val fixed = broken.normalizeScreens()
        assertEquals(listOf("first"), fixed.screens.map { it.title }, "one record per id, the first kept, no blank ids")
        assertNotNull(fixed.surfaces[SurfaceId("screen.a")], "and a screen whose page went missing gets a blank one")
        assertSame(fixed, fixed.normalizeScreens())
    }

    @Test
    fun `a file written before screens existed reads with none`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val g = json.decodeFromString(LayoutGraph.serializer(), """{"surfaces":{}}""")
        assertTrue(g.screens.isEmpty())
        val round = json.decodeFromString(LayoutGraph.serializer(), json.encodeToString(LayoutGraph.serializer(), base.addScreen(spec("a", "Mine"))))
        assertEquals("Mine", round.screen("a")?.title)
    }
}
