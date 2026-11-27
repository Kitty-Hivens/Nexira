package hivens.ui.editor

import hivens.ui.i18n.NexiraStrings
import hivens.ui.navigation.LauncherConfigs
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotId
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import hivens.widget.model.holdOwn
import hivens.widget.model.withConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whom an edit reaches, read off the standard document: a screen's page is its own, the rails are shared by every
 * screen until one holds a slot as its own, and a frame widget is one record for all of them.
 */
class EditReachTest {

    private val standard = StandardDocument.load()
    private val strings = NexiraStrings.shipped("en")

    private fun root(config: ConfigId) = NodePath(LauncherConfigs.Composition, NodeRoot.Layout(config, standard.compositions.getValue(LauncherConfigs.Composition).configs.getValue(config).layouts.first().id))
    private fun row(config: ConfigId) = root(config).child("appshell-region-body-default", SlotId("content"))
    private fun page(config: ConfigId) = row(config).child("appshell-region-center-default", SlotId("body")).child("screen.${config.value}", SlotId("main"))
    private val rail = row(LauncherConfigs.Home).child("appshell-region-right-default", SlotId("main"))

    @Test
    fun `a widget on a screen's page changes that screen only`() {
        val hero = standard.contentAt(page(LauncherConfigs.Home))?.widgets?.first()
        assertTrue(hero != null, "the home page holds widgets in the standard document")
        assertEquals(EditReach.ThisScreen, standard.reachOf(page(LauncherConfigs.Home), hero))
    }

    @Test
    fun `a widget in a shared rail slot changes every screen, and this screen's own once it holds the slot`() {
        val news = standard.contentAt(rail)?.widgets?.first()
        assertEquals(EditReach.EveryScreen, standard.reachOf(rail, news))
        val own = standard.holdOwn(rail) { "$it-own" }
        assertEquals(EditReach.ThisScreen, own.reachOf(rail, own.contentAt(rail)?.widgets?.first()))
    }

    @Test
    fun `a frame widget is one record, wherever it stands`() {
        val right = standard.contentAt(row(LauncherConfigs.Home))?.widgets?.first { it.kind == WidgetKind("appshell.region.right") }
        assertEquals(EditReach.EveryScreen, standard.reachOf(row(LauncherConfigs.Home), right))
    }

    @Test
    fun `a screen is called what its maker named it, or what the launcher calls it`() {
        val made = standard.withConfig(LauncherConfigs.Composition, ConfigId("screen-1"), Config(title = "Notes"))
        assertEquals("Notes", made.configLabel(ConfigId("screen-1"), strings))
        assertEquals("Home", standard.configLabel(LauncherConfigs.Home, strings))
        assertEquals("Project", standard.configLabel(LauncherConfigs.Project, strings))
        assertEquals("Build", standard.configLabel(LauncherConfigs.Build, strings))
        assertEquals("unknown", standard.configLabel(ConfigId("unknown"), strings), "an id with no name is shown as itself")
    }
}
