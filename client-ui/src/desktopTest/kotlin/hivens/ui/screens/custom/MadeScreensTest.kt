package hivens.ui.screens.custom

import hivens.ui.Screen
import hivens.ui.editor.EditorSurfaces
import hivens.ui.i18n.EnglishStrings
import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScreenSpec
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.addScreen
import hivens.widget.model.insertWidget
import hivens.widget.model.removeScreen
import hivens.widget.model.screen
import hivens.widget.model.traverse
import hivens.widget.model.walkInstances
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A made screen as the app sees it: a link on the rail, a tab in the editor, and
 * nothing left pointing at it once it is gone.
 */
class MadeScreensTest {

    private val spec = ScreenSpec("mine", "Mine", icon = "star", surface = SurfaceId("screen.mine"))
    private val rail = SlotPath(SurfaceId("appshell.leftrail"), SlotId("top"))
    private val graph = DefaultLayout.load().addScreen(spec)

    private fun links(g: LayoutGraph) = g.walkInstances().mapNotNull { ScreenLinks.target(it) }.toList()

    @Test
    fun `a made screen gets one link at the bottom of the rail`() {
        val linked = ScreenLinks.ensureLink(graph, spec)
        assertEquals(listOf("mine"), links(linked))
        assertEquals(ScreenLinks.KIND, linked.traverse(rail)!!.widgets.last().kind, "at the end of the rail's list")
        assertSame(linked, ScreenLinks.ensureLink(linked, spec), "and a second pass adds nothing")
    }

    @Test
    fun `a link moved off the rail is still the screen's link`() {
        val home = SlotPath(SurfaceId("home.new"), SlotId("main"))
        val elsewhere = graph.insertWidget(
            home,
            WidgetInstance(ScreenLinks.KIND, "moved", props = buildJsonObject { put("screen", JsonPrimitive("mine")) }),
            0,
        )
        assertSame(elsewhere, ScreenLinks.ensureLink(elsewhere, spec), "putting one back on the rail would make two")
    }

    @Test
    fun `deleting a screen clears every link to it and only those`() {
        val other = ScreenSpec("other", surface = SurfaceId("screen.other"))
        val g = ScreenLinks.ensureLinks(graph.addScreen(other))
        val out = ScreenLinks.removeLinks(g, "mine")
        assertEquals(listOf("other"), links(out))
    }

    @Test
    fun `the editor offers a made screen's page while it is open, under its own name`() {
        val surfaces = EditorSurfaces.availableFor(Screen.Custom("mine"), graph)
        assertEquals(spec.surface, surfaces.first(), "the page is the first tab, like any screen's")
        val s = EditorSurfaces.specIn(spec.surface, graph)!!
        assertEquals("Mine", s.name(EnglishStrings))
        assertTrue(s.hasSettings, "a made screen has its own settings: name, icon, delete")
        assertEquals(ScreenIcons.of("star"), s.icon)
    }

    @Test
    fun `an unnamed screen is called untitled rather than nothing`() {
        val g = DefaultLayout.load().addScreen(spec.copy(title = ""))
        assertEquals(EnglishStrings.screenUntitled, EditorSurfaces.specIn(spec.surface, g)!!.shortName(EnglishStrings))
    }

    @Test
    fun `an icon name this build does not know draws the default`() {
        assertEquals(ScreenIcons.of(ScreenIcons.DEFAULT), ScreenIcons.of("sparkle-unicorn"))
    }

    @Test
    fun `the bundled rail has the slot links go to`() {
        // Links land in this slot by name. If the rail's slots are ever renamed, a
        // made screen would be created with no way to reach it.
        assertTrue(DefaultLayout.load().surfaces[rail.surface]!!.slotsOf(FamilyId.GENERAL).containsKey(rail.rootSlot))
    }

    @Test
    fun `a deleted screen comes back as it stood, links in their places`() {
        val home = SlotPath(SurfaceId("home.new"), SlotId("main"))
        val filled = ScreenLinks.ensureLink(graph, spec)
            .insertWidget(SlotPath(spec.surface, SlotId("main")), WidgetInstance(WidgetKind("k"), "on-it"), 0)
            .insertWidget(home, WidgetInstance(ScreenLinks.KIND, "on-home", props = buildJsonObject { put("screen", JsonPrimitive("mine")) }), 1)
        val gone = ScreenTrash.capture(filled, "mine")!!
        val deleted = ScreenLinks.removeLinks(filled.removeScreen("mine"), "mine")

        val back = ScreenTrash.restore(deleted, gone)

        assertEquals("Mine", back.screen("mine")?.title)
        assertEquals(listOf("on-it"), back.traverse(SlotPath(spec.surface, SlotId("main")))!!.widgets.map { it.instanceId })
        assertEquals("on-home", back.traverse(home)!!.widgets[1].instanceId, "the link on Home is back in its place")
        assertEquals(2, links(back).size, "and the one on the rail, not a third")
    }

    @Test
    fun `a screen whose id was taken again is not put back over it`() {
        val gone = ScreenTrash.capture(graph, "mine")!!
        assertSame(graph, ScreenTrash.restore(graph, gone))
    }
}
