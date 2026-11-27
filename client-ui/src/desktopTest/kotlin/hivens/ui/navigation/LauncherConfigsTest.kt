package hivens.ui.navigation

import hivens.catalogue.api.CataloguePackRef
import hivens.catalogue.api.CataloguePages
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTarget
import hivens.packs.api.PackPages
import hivens.ui.chrome.staticCrumbLabel
import hivens.ui.i18n.NexiraStrings
import hivens.ui.screens.screenKind
import hivens.ui.widgets.themepicker.launcherAndControls
import hivens.widget.model.ConfigId
import hivens.history.api.History
import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import hivens.widget.model.StandardDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import hivens.pages.api.ScreenId

/** The launcher's own configs: each has a widget that draws it, a name in the trail, and is opened with what its page reads. */
class LauncherConfigsTest {

    private val strings = NexiraStrings.shipped("en")

    private fun Show.entry() = HistoryEntry(screen, values)

    @Test
    fun `every config has its widget, and every screen widget its config`() {
        val screens = launcherAndControls.all().keys.map { it.value }.filter { it.startsWith("screen.") }.toSet()
        assertEquals((LauncherScreens.all.map { it.asConfig() } + LauncherConfigs.Console).map { screenKind(it).value }.toSet(), screens)
    }

    @Test
    fun `the console's composition starts on the console`() {
        val console = StandardDocument.load().compositions.getValue(LauncherConfigs.ConsoleComposition)
        assertEquals(LauncherConfigs.Console, console.start)
        assertEquals(LauncherConfigs.Composition, StandardDocument.load().start)
    }

    @Test
    fun `no two configs share an id`() {
        val ids = LauncherScreens.all.map { it.value }
        assertEquals(14, ids.toSet().size)
    }

    @Test
    fun `a screen widget is not offered in the palette`() {
        LauncherScreens.all.forEach { assertEquals(false, assertNotNull(launcherAndControls[screenKind(it.asConfig())]).removable, it.value) }
    }

    @Test
    fun `a config nothing has to be looked up for names its crumb`() {
        val named = LauncherScreens.all - setOf(LauncherScreens.Pack, LauncherScreens.CataloguePack, LauncherScreens.Project, LauncherScreens.Build)
        val labels = named.associateWith { assertNotNull(staticCrumbLabel(HistoryEntry(it), strings), it.value) }
        labels.forEach { (config, label) -> assertNotEquals("", label, config.value) }
        assertEquals(labels.size, labels.values.toSet().size, "two places are called the same: $labels")
        assertEquals("1.2", staticCrumbLabel(LauncherScreens.build(ProjectTarget.Catalogue("sodium"), "v1", "1.2").entry(), strings))
        assertNull(staticCrumbLabel(LauncherScreens.pack("a").entry(), strings))
        assertNull(staticCrumbLabel(HistoryEntry(ScreenId("made")), strings))
    }

    @Test
    fun `the catalogue from inside a pack is a page of its own, called otherwise`() {
        val into = LauncherScreens.browseInto("a").entry()
        assertNotEquals(HistoryEntry(LauncherScreens.Browse).page, into.page)
        assertNotEquals(staticCrumbLabel(HistoryEntry(LauncherScreens.Browse), strings), staticCrumbLabel(into, strings))
        assertEquals("a", CataloguePages.Into.read(into.values))
    }

    @Test
    fun `what a page is opened with reads back`() {
        assertEquals("a", PackPages.Pack.read(LauncherScreens.pack("a").values))
        assertEquals("a", PackPages.Pack.read(LauncherScreens.packVersions("a").values))
        assertEquals(CataloguePackRef("modrinth", "p"), CataloguePages.Pack.read(LauncherScreens.cataloguePack("modrinth", "p").values))
        val targets = listOf(
            ProjectTarget.Catalogue("sodium"),
            ProjectTarget.Catalogue("sodium", intoInstanceId = "a"),
            ProjectTarget.Installed("a", ProjectKind.Mod, "sodium.jar"),
        )
        targets.forEach { target ->
            assertEquals<ProjectTarget?>(target, CataloguePages.Project.read(LauncherScreens.project(target).values))
            val build = LauncherScreens.build(target, "v1", "1.2").values
            assertEquals<ProjectTarget?>(target, CataloguePages.Project.read(build))
            assertEquals("v1", CataloguePages.Build.read(build)?.versionId)
        }
        assertEquals(targets.size, targets.map { LauncherScreens.project(it).entry().page }.toSet().size, "each target is a page of its own")
    }

    @Test
    fun `a section stands over home`() {
        val history = History(LauncherScreens.Home).show(LauncherScreens.pack("a")).show(LauncherScreens.section(LauncherScreens.Settings))
        assertEquals(listOf(LauncherScreens.Home, LauncherScreens.Settings), history.entries.map { it.screen })
    }
}
