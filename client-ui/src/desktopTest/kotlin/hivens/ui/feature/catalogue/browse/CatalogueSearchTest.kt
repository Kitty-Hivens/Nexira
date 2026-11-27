package hivens.ui.feature.catalogue.browse

import hivens.catalogue.BrowseState
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import hivens.catalogue.api.ENV_CLIENT
import hivens.catalogue.api.ENV_SERVER
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.FilterPick
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SourceView
import hivens.catalogue.api.TagSearch
import hivens.ui.feature.catalogue.project.Environment
import hivens.ui.navigation.LauncherScreens
import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a tag on a project page asks the catalogue, and what the search it opens
 * looks like when Browse comes up.
 */
class CatalogueSearchTest {

    private val tags = CatalogueTags(
        gameVersions = emptyList(),
        categories = listOf(
            CategoryTag("64x", projectType = "resourcepack", header = "resolutions"),
            CategoryTag("realistic", projectType = "resourcepack", header = "categories"),
            CategoryTag("optimization", projectType = "mod", header = "categories"),
        ),
        loaders = emptyList(),
    )

    /**
     * A window's browse as the catalogue module keeps it. Its work never runs, so a pack
     * named as the target stays named without being read off a disk the test has not got.
     */
    private fun browse() = BrowseState(
        sources = MutableStateFlow(listOf(SourceView("Shelf", "test.shelf", "name"), SourceView("Searched", "test.searched", "name", packType = MODPACK))),
        packOf = { null },
        instancesDir = Path.of("/nonexistent"),
        scope = CoroutineScope(Job().apply { cancel() }),
    )

    @Test
    fun `a category is a category, and one filed under resolutions is a resolution`() {
        assertEquals(
            setOf(SearchFilter(FilterField.Category, "optimization")),
            filtersFor(ProjectTag.Category("optimization"), "mod", tags),
        )
        assertEquals(
            setOf(SearchFilter(FilterField.Resolution, "64x")),
            filtersFor(ProjectTag.Category("64x"), "resourcepack", tags),
        )
        assertEquals(
            setOf(SearchFilter(FilterField.Category, "64x")),
            filtersFor(ProjectTag.Category("64x"), "resourcepack", null),
            "without the catalogue's lists a lone category finds the same projects",
        )
    }

    @Test
    fun `a chip of folded versions asks for every version behind it`() {
        assertEquals(
            setOf("1.21", "1.21.1", "1.21.2").map { SearchFilter(FilterField.GameVersion, it) }.toSet(),
            filtersFor(ProjectTag.GameVersions(listOf("1.21", "1.21.1", "1.21.2")), "mod", null),
        )
    }

    @Test
    fun `where it runs is the catalogue's environment, and both sides is both`() {
        assertEquals(setOf(SearchFilter(FilterField.Environment, ENV_CLIENT)), filtersFor(ProjectTag.Runs(Environment.Client), "mod", null))
        assertEquals(setOf(SearchFilter(FilterField.Environment, ENV_SERVER)), filtersFor(ProjectTag.Runs(Environment.Server), "modpack", null))
        assertEquals(
            setOf(SearchFilter(FilterField.Environment, ENV_CLIENT), SearchFilter(FilterField.Environment, ENV_SERVER)),
            filtersFor(ProjectTag.Runs(Environment.Both), "mod", null),
        )
        assertNull(filtersFor(ProjectTag.Runs(Environment.Client), "shader", null), "a shader has no environment filter")
    }

    @Test
    fun `a tag is a label where the catalogue has no filter for it`() {
        assertNull(filtersFor(ProjectTag.Loader("minecraft"), "resourcepack", null), "a resource pack has no platform filter")
        assertNull(filtersFor(ProjectTag.Category("economy"), "plugin", null), "Browse does not list plugins")
        assertEquals(setOf(SearchFilter(FilterField.Loader, "iris")), filtersFor(ProjectTag.Loader("iris"), "shader", null))
    }

    @Test
    fun `a tag search starts fresh on its own type`() {
        val c = browse()
        c.setKind(ProjectKind.Mod)
        c.setQuery("sodium")
        c.toggle(FilterPick("shader", SearchFilter(FilterField.Category, "cartoon")))
        c.toggle(FilterPick("shader", SearchFilter(FilterField.Loader, "optifine")))
        c.toggle(FilterPick("mod", SearchFilter(FilterField.Category, "magic")))

        c.searchFor(TagSearch("shader", setOf(SearchFilter(FilterField.Loader, "iris")), packId = null))

        val view = c.view.value
        assertEquals(ProjectKind.ShaderPack, view.kind)
        assertEquals("", view.query, "the tag is the question, not what was typed before")
        assertEquals(setOf(SearchFilter(FilterField.Loader, "iris")), view.chosenFor("shader"))
        assertEquals(
            setOf(SearchFilter(FilterField.Category, "magic")),
            view.chosenFor("mod"),
            "another type's choices are that type's and stay",
        )
    }

    @Test
    fun `a modpack tag lists the packs of the source that searches them as modpacks`() {
        val c = browse()
        c.setOrigin("Shelf")
        c.searchFor(TagSearch(MODPACK, setOf(SearchFilter(FilterField.Category, "adventure")), packId = null))
        assertNull(c.view.value.kind)
        assertEquals("Searched", c.view.value.origin)
    }

    @Test
    fun `the page's pack becomes the target and gives up the field the tag asks about`() {
        val c = browse()
        c.setTarget("other")
        c.unlock(FilterField.Loader)

        c.searchFor(TagSearch("mod", setOf(SearchFilter(FilterField.GameVersion, "1.20.1")), packId = "pack"))

        assertEquals("pack", c.view.value.targetId)
        assertEquals(setOf(FilterField.GameVersion), c.view.value.unlocked, "the unlock for the other pack went with it")

        c.searchFor(TagSearch("mod", setOf(SearchFilter(FilterField.Category, "magic")), packId = null))
        assertNull(c.view.value.targetId, "a page with no pack behind it aims at none")
        assertTrue(c.view.value.unlocked.isEmpty())
    }

    private val onProject = LauncherScreens.project(ProjectTarget.Catalogue("sodium")).let { HistoryEntry(it.screen, it.values) }

    /** The lists can take seconds, and the search waits on the shell's scope, not the page's. */
    @Test
    fun `a tag search does not take the reader away from where they went while it waited`() = runBlocking {
        var entry = onProject
        val opened = mutableListOf<Show>()
        val asked = mutableListOf<TagSearch>()

        searchFromTag(
            "mod", ProjectTag.Category("optimization"), packId = null,
            known = { entry = HistoryEntry(LauncherScreens.Library); tags },
            current = { entry },
            show = { opened += it },
            search = { asked += it },
        )

        assertEquals(emptyList(), opened)
        assertEquals(emptyList(), asked, "Browse's own question is left as it was")
    }

    @Test
    fun `a tag search opens Browse when the reader is still on the page`() = runBlocking {
        val opened = mutableListOf<Show>()
        val asked = mutableListOf<TagSearch>()

        searchFromTag(
            "mod", ProjectTag.Category("optimization"), packId = null,
            known = { tags },
            current = { onProject },
            show = { opened += it },
            search = { asked += it },
        )

        assertEquals(listOf(Show(LauncherScreens.Browse)), opened)
        assertEquals(listOf(TagSearch("mod", setOf(SearchFilter(FilterField.Category, "optimization")), null)), asked)
    }
}
