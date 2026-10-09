package hivens.ui.feature.catalogue.browse

import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.data.PackOrigin
import hivens.launcher.instance.ContentKind
import hivens.launcher.modrinth.ENV_CLIENT
import hivens.launcher.modrinth.ENV_SERVER
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.SearchFilter
import hivens.ui.Screen
import hivens.ui.feature.catalogue.project.Environment
import hivens.ui.feature.catalogue.project.ModTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a tag on a project page asks the catalogue, and what the search it opens
 * looks like when Browse comes up.
 */
class CatalogueSearchTest {

    private val tags = BrowseTags.Tags(
        gameVersions = emptyList(),
        categories = listOf(
            ModrinthCategoryTag("64x", projectType = "resourcepack", header = "resolutions"),
            ModrinthCategoryTag("realistic", projectType = "resourcepack", header = "categories"),
            ModrinthCategoryTag("optimization", projectType = "mod", header = "categories"),
        ),
        loaders = emptyList(),
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
        val c = BrowseController()
        c.kind = ContentKind.Mod
        c.query = "sodium"
        c.toggle("shader", SearchFilter(FilterField.Category, "cartoon"))
        c.toggle("shader", SearchFilter(FilterField.Loader, "optifine"))
        c.toggle("mod", SearchFilter(FilterField.Category, "magic"))

        c.searchFor("shader", setOf(SearchFilter(FilterField.Loader, "iris")), packId = null)

        assertEquals(ContentKind.ShaderPack, c.kind)
        assertEquals("", c.query, "the tag is the question, not what was typed before")
        assertEquals(setOf(SearchFilter(FilterField.Loader, "iris")), c.chosenFor("shader"))
        assertEquals(
            setOf(SearchFilter(FilterField.Category, "magic")),
            c.chosenFor("mod"),
            "another type's choices are that type's and stay",
        )
    }

    @Test
    fun `a modpack tag lists the catalogue's packs`() {
        val c = BrowseController()
        c.origin = PackOrigin.Mirror
        c.searchFor(MODPACK, setOf(SearchFilter(FilterField.Category, "adventure")), packId = null)
        assertNull(c.kind)
        assertEquals(PackOrigin.Modrinth, c.origin)
    }

    @Test
    fun `the page's pack becomes the target and gives up the field the tag asks about`() {
        val c = BrowseController()
        c.targetId = "other"
        c.unlocked += FilterField.Loader

        c.searchFor("mod", setOf(SearchFilter(FilterField.GameVersion, "1.20.1")), packId = "pack")

        assertEquals("pack", c.targetId)
        assertEquals(listOf(FilterField.GameVersion), c.unlocked.toList(), "the unlock for the other pack went with it")

        c.searchFor("mod", setOf(SearchFilter(FilterField.Category, "magic")), packId = null)
        assertNull(c.targetId, "a page with no pack behind it aims at none")
        assertTrue(c.unlocked.isEmpty())
    }

    /** The lists can take seconds, and the search waits on the shell's scope, not the page's. */
    @Test
    fun `a tag search does not take the reader away from where they went while it waited`() = runBlocking {
        val c = BrowseController()
        c.query = "sodium"
        var screen: Screen = Screen.ModDetail(ModTarget.Catalogue("sodium"))
        val opened = mutableListOf<Screen>()

        searchFromTag(
            c, "mod", ProjectTag.Category("optimization"), packId = null,
            known = { screen = Screen.Library; tags },
            currentScreen = { screen },
            onScreenChange = { opened += it },
        )

        assertEquals(emptyList(), opened)
        assertEquals("sodium", c.query, "Browse's own question is left as it was")
    }

    @Test
    fun `a tag search opens Browse when the reader is still on the page`() = runBlocking {
        val c = BrowseController()
        val opened = mutableListOf<Screen>()

        searchFromTag(
            c, "mod", ProjectTag.Category("optimization"), packId = null,
            known = { tags },
            currentScreen = { Screen.ModDetail(ModTarget.Catalogue("sodium")) },
            onScreenChange = { opened += it },
        )

        assertEquals(listOf<Screen>(Screen.Browse), opened)
        assertEquals(setOf(SearchFilter(FilterField.Category, "optimization")), c.chosenFor("mod"))
    }
}
