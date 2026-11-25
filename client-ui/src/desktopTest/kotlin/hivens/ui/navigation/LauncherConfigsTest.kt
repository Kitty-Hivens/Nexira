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
import hivens.widget.model.History
import hivens.widget.model.HistoryEntry
import hivens.widget.model.Show
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The launcher's own configs: each has a widget that draws it, a name in the trail, and is opened with what its page reads. */
class LauncherConfigsTest {

    private val strings = NexiraStrings.shipped("en")

    private fun Show.entry() = HistoryEntry(config, values)

    @Test
    fun `every config has its widget, and every screen widget its config`() {
        val screens = launcherAndControls.all().keys.map { it.value }.filter { it.startsWith("screen.") }.toSet()
        assertEquals(LauncherConfigs.all.map { screenKind(it).value }.toSet(), screens)
    }

    @Test
    fun `no two configs share an id`() {
        val ids = LauncherConfigs.all.map { it.value }
        assertEquals(14, ids.toSet().size)
    }

    @Test
    fun `a screen widget is not offered in the palette`() {
        LauncherConfigs.all.forEach { assertEquals(false, assertNotNull(launcherAndControls[screenKind(it)]).removable, it.value) }
    }

    @Test
    fun `a config nothing has to be looked up for names its crumb`() {
        val named = LauncherConfigs.all - setOf(LauncherConfigs.Pack, LauncherConfigs.CataloguePack, LauncherConfigs.Project, LauncherConfigs.Build)
        val labels = named.associateWith { assertNotNull(staticCrumbLabel(HistoryEntry(it), strings), it.value) }
        labels.forEach { (config, label) -> assertNotEquals("", label, config.value) }
        assertEquals(labels.size, labels.values.toSet().size, "two places are called the same: $labels")
        assertEquals("1.2", staticCrumbLabel(LauncherConfigs.build(ProjectTarget.Catalogue("sodium"), "v1", "1.2").entry(), strings))
        assertNull(staticCrumbLabel(LauncherConfigs.pack("a").entry(), strings))
        assertNull(staticCrumbLabel(HistoryEntry(ConfigId("made")), strings))
    }

    @Test
    fun `the catalogue from inside a pack is a page of its own, called otherwise`() {
        val into = LauncherConfigs.browseInto("a").entry()
        assertNotEquals(HistoryEntry(LauncherConfigs.Browse).page, into.page)
        assertNotEquals(staticCrumbLabel(HistoryEntry(LauncherConfigs.Browse), strings), staticCrumbLabel(into, strings))
        assertEquals("a", CataloguePages.Into.read(into.values))
    }

    @Test
    fun `what a page is opened with reads back`() {
        assertEquals("a", PackPages.Pack.read(LauncherConfigs.pack("a").values))
        assertEquals("a", PackPages.Pack.read(LauncherConfigs.packVersions("a").values))
        assertEquals(CataloguePackRef("modrinth", "p"), CataloguePages.Pack.read(LauncherConfigs.cataloguePack("modrinth", "p").values))
        val targets = listOf(
            ProjectTarget.Catalogue("sodium"),
            ProjectTarget.Catalogue("sodium", intoInstanceId = "a"),
            ProjectTarget.Installed("a", ProjectKind.Mod, "sodium.jar"),
        )
        targets.forEach { target ->
            assertEquals<ProjectTarget?>(target, CataloguePages.Project.read(LauncherConfigs.project(target).values))
            val build = LauncherConfigs.build(target, "v1", "1.2").values
            assertEquals<ProjectTarget?>(target, CataloguePages.Project.read(build))
            assertEquals("v1", CataloguePages.Build.read(build)?.versionId)
        }
        assertEquals(targets.size, targets.map { LauncherConfigs.project(it).entry().page }.toSet().size, "each target is a page of its own")
    }

    @Test
    fun `a section stands over home`() {
        val history = History(LauncherConfigs.Home).show(LauncherConfigs.pack("a")).show(LauncherConfigs.section(LauncherConfigs.Settings))
        assertEquals(listOf(LauncherConfigs.Home, LauncherConfigs.Settings), history.entries.map { it.config })
    }
}
