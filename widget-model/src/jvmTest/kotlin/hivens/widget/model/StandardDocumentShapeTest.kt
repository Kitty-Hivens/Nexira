package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shape of the standard document. The frame stands in every config of the launcher: the top bar, then the body, a
 * row of the left rail, the centre and the right rail, each a single kept once. Every config holds the body's row as its
 * own, the centre holds that config's screen, and the right rail is one record whose widgets are shown on the configs
 * they name, the way a bar's widget follows what the compositor shows.
 */
class StandardDocumentShapeTest {

    private val document = StandardDocument.load()
    private val launcher = document.compositions.getValue(LAUNCHER)

    private fun row(config: ConfigId) = NodePath(LAUNCHER, NodeRoot.Layout(config, MAIN)).child(BODY, SlotId("content"))
    private fun rail(config: ConfigId) = row(config).child(RIGHT, SlotId("main"))

    @Test
    fun `every config stands the frame and draws its own screen in the centre`() {
        assertTrue(launcher.configs.isNotEmpty())
        for (config in launcher.configs.keys) {
            val root = assertNotNull(document.contentAt(NodePath(LAUNCHER, NodeRoot.Layout(config, MAIN))), config.value)
            assertEquals(listOf("appshell.region.top", "appshell.region.body"), root.widgets.map { it.kind.value }, config.value)
            assertEquals(listOf("appshell.region.left", "appshell.region.center", "appshell.region.right"), document.contentAt(row(config))?.widgets?.map { it.kind.value }, config.value)
            val page = assertNotNull(document.contentAt(row(config).child(CENTER, SlotId("body"))), config.value)
            assertEquals(listOf("screen.${config.value}"), page.widgets.map { it.kind.value }, config.value)
        }
        assertEquals(FRAME, launcher.singles.keys.map { it.value }.toSet(), "the frame is kept once")
    }

    @Test
    fun `the console draws its screen alone`() {
        val console = NodePath(CompositionName("console"), NodeRoot.Layout(ConfigId("console"), MAIN))
        assertEquals(listOf("screen.console"), document.contentAt(console)?.widgets?.map { it.kind.value })
    }

    @Test
    fun `the right rail is one record, showing on each config what is meant for it`() {
        val home = ConfigId("home")
        val project = ConfigId("project")
        val onHome = assertNotNull(document.contentAt(rail(home)))
        assertEquals(onHome, document.contentAt(rail(project)), "the same record on every config")
        assertFalse(document.heldOwnAnywhere(rail(home)), "no config holds it as its own")
        val homeKinds = onHome.widgets.filter { it.shownOn(home) }.map { it.kind }
        val projectKinds = onHome.widgets.filter { it.shownOn(project) }.map { it.kind }
        assertTrue(homeKinds.isNotEmpty() && projectKinds.isNotEmpty())
        assertTrue(homeKinds.none { it in projectKinds }, "the feed is hidden where the project's blocks are shown, and the other way round")
    }

    @Test
    fun `every widget of a composition goes by a name of its own`() {
        assertSame(document, document.withOwnNames { error("$it is said twice") })
    }

    private companion object {
        val LAUNCHER = CompositionName("launcher")
        val MAIN = LayoutId("main")
        const val BODY = "appshell-region-body-default"
        const val CENTER = "appshell-region-center-default"
        const val RIGHT = "appshell-region-right-default"
        val FRAME = setOf("appshell.region.top", "appshell.region.body", "appshell.region.left", "appshell.region.center", "appshell.region.right")
    }
}
