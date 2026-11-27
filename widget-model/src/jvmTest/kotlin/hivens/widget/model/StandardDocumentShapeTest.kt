package hivens.widget.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shape of the standard document. The launcher draws one config, the frame: the top bar, then the body, a row of
 * the left rail, the centre and the right rail, each a single kept once. The centre holds the pages host, whose slot
 * for each screen holds that screen, and the right rail is one record whose widgets are shown on the screens they name,
 * the way a bar's widget follows what the compositor shows.
 */
class StandardDocumentShapeTest {

    private val document = StandardDocument.load()
    private val launcher = document.compositions.getValue(LAUNCHER)

    private val root = NodePath(LAUNCHER, NodeRoot.Layout(MAIN_CONFIG, MAIN))
    private val row = root.child(BODY, SlotId("content"))
    private val body = row.child(CENTER, SlotId("body"))
    private val rail = row.child(RIGHT, SlotId("main"))

    @Test
    fun `the launcher draws one config, the frame, with the pages host in the centre`() {
        assertEquals(setOf(MAIN_CONFIG), launcher.configs.keys)
        assertEquals(MAIN_CONFIG, launcher.start)
        assertEquals(listOf("appshell.region.top", "appshell.region.body"), document.contentAt(root)?.widgets?.map { it.kind.value })
        assertEquals(listOf("appshell.region.left", "appshell.region.center", "appshell.region.right"), document.contentAt(row)?.widgets?.map { it.kind.value })
        assertEquals(listOf(HOST), document.contentAt(body)?.widgets?.map { it.kind.value })
        assertEquals(FRAME, launcher.singles.keys.map { it.value }.toSet(), "the frame is kept once")
    }

    @Test
    fun `the host holds each screen in the slot named by its id, and starts on home`() {
        val host = assertNotNull(document.contentAt(body)?.widgets?.single())
        assertEquals(SCREENS, host.children.keys.map { it.value }.toSet())
        for ((slot, held) in host.children) {
            assertEquals(listOf("screen.${slot.value}"), held.widgets.map { it.kind.value }, slot.value)
        }
        assertEquals("home", (host.props["start"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `the console draws its screen alone`() {
        val console = NodePath(CompositionName("console"), NodeRoot.Layout(ConfigId("console"), MAIN))
        assertEquals(listOf("screen.console"), document.contentAt(console)?.widgets?.map { it.kind.value })
    }

    @Test
    fun `the right rail is one record, showing on each screen what is meant for it`() {
        val widgets = assertNotNull(document.contentAt(rail)).widgets
        fun on(screen: String) = widgets.filter { it.shownAt(setOf(MAIN_CONFIG.value, screen)) }.map { it.kind }
        val home = on("home")
        val project = on("project")
        assertTrue(home.isNotEmpty() && project.isNotEmpty())
        assertTrue(home.none { it in project }, "the feed is hidden where the project's blocks are shown, and the other way round")
    }

    @Test
    fun `the history module is among the modules, so the pages are shown and remembered`() {
        assertTrue("nexira.history" in document.modules)
    }

    @Test
    fun `every widget of a composition goes by a name of its own`() {
        assertSame(document, document.withOwnNames { error("$it is said twice") })
    }

    private companion object {
        val LAUNCHER = CompositionName("launcher")
        val MAIN_CONFIG = ConfigId("main")
        val MAIN = LayoutId("main")
        const val BODY = "appshell-region-body-default"
        const val CENTER = "appshell-region-center-default"
        const val RIGHT = "appshell-region-right-default"
        const val HOST = "pages.host"
        val FRAME = setOf("appshell.region.top", "appshell.region.body", "appshell.region.left", "appshell.region.center", "appshell.region.right")
        val SCREENS = setOf(
            "home", "library", "browse", "profile", "wardrobe", "settings", "theme.picker", "about", "background",
            "pack", "pack.versions", "catalogue.pack", "project", "project.build",
        )
    }
}
