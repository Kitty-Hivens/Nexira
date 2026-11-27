package hivens.ui.editor

import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.ui.i18n.NexiraStrings
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.LauncherScreens
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotId
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import hivens.widget.model.holdOwn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whom an edit reaches, read off the standard document: what is in the pages host's slot for a screen is that screen's
 * own, the frame around the host is every screen's, held as the config's own or not, and a frame widget is one record
 * for all of them.
 */
class EditReachTest {

    private val standard = StandardDocument.load()
    private val strings = NexiraStrings.shipped("en")
    private val launcher = standard.compositions.getValue(LauncherConfigs.Composition)

    private val root = NodePath(LauncherConfigs.Composition, NodeRoot.Layout(launcher.start, launcher.configs.getValue(launcher.start).layouts.first().id))
    private val row = root.child("appshell-region-body-default", SlotId("content"))
    private val centre = row.child("appshell-region-center-default", SlotId("body"))
    private fun screen(id: ScreenId) = centre.child("pages-host-default", SlotId(id.value))
    private fun page(id: ScreenId) = screen(id).child("screen.${id.value}", SlotId("main"))
    private val rail = row.child("appshell-region-right-default", SlotId("main"))

    @Test
    fun `a widget on a screen changes that screen only`() {
        val hero = assertNotNull(standard.contentAt(page(LauncherScreens.Home))?.widgets?.first(), "the home page holds widgets in the standard document")
        assertEquals(EditReach.ThisScreen, standard.reachOf(page(LauncherScreens.Home), hero))
        assertEquals(EditReach.ThisScreen, standard.reachOf(screen(LauncherScreens.Home), standard.contentAt(screen(LauncherScreens.Home))?.widgets?.first()))
        assertTrue(standard.onPage(page(LauncherScreens.Home)))
    }

    @Test
    fun `the frame around the host changes every screen, held as the config's own or not`() {
        val news = standard.contentAt(rail)?.widgets?.first()
        assertEquals(EditReach.EveryScreen, standard.reachOf(rail, news))
        val own = standard.holdOwn(rail) { "$it-own" }
        assertEquals(EditReach.EveryScreen, own.reachOf(rail, own.contentAt(rail)?.widgets?.first()))
        assertEquals(EditReach.EveryScreen, standard.reachOf(centre, standard.contentAt(centre)?.widgets?.first()), "the host itself is the frame's")
        assertFalse(standard.onPage(rail))
        assertFalse(standard.onPage(centre))
    }

    @Test
    fun `a frame widget is one record, wherever it stands`() {
        val right = standard.contentAt(row)?.widgets?.first { it.kind == WidgetKind("appshell.region.right") }
        assertEquals(EditReach.EveryScreen, standard.reachOf(row, right))
    }

    @Test
    fun `a screen is called what a person named it, or what the launcher calls it`() {
        val screens = listOf(
            ScreenView(ScreenId("screen-1"), "Notes", owner = null, made = true),
            ScreenView(LauncherScreens.Library, "Shelf", owner = null),
            ScreenView(LauncherScreens.Home, "screen.home", owner = "nexira.launcher.screens"),
        )
        assertEquals("Notes", placeLabel("screen-1", screens, strings))
        assertEquals("Shelf", placeLabel(LauncherScreens.Library.value, screens, strings), "renamed")
        assertEquals("Home", placeLabel(LauncherScreens.Home.value, screens, strings))
        assertEquals("Project", placeLabel(LauncherScreens.Project.value, screens, strings))
        assertEquals("Build", placeLabel(LauncherScreens.Build.value, screens, strings))
        assertEquals("unknown", placeLabel("unknown", screens, strings), "an id with no name is shown as itself")
    }
}
