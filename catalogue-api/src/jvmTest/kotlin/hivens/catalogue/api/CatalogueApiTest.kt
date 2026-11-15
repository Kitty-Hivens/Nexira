package hivens.catalogue.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogueApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.catalogueApi")) { "the build did not pass nexira.catalogueApi" }
        assertEquals(CatalogueApi.VERSION, ApiVersion.parse(stamped))
    }

    /** Every key with the id its constant names and the level it is documented at. */
    private val keys: List<Triple<String, Any, ScopeLevel>> = listOf(
        Triple(CatalogueKeys.TAGS_ID, CatalogueKeys.Tags, ScopeLevel.App),
        Triple(CatalogueKeys.LOAD_TAGS_ID, CatalogueKeys.LoadTags, ScopeLevel.App),
        Triple(CatalogueKeys.SOURCES_ID, CatalogueKeys.Sources, ScopeLevel.App),
        Triple(CatalogueKeys.NAMES_ID, CatalogueKeys.Names, ScopeLevel.App),
        Triple(CatalogueKeys.BROWSE_ID, CatalogueKeys.Browse, ScopeLevel.Window),
        Triple(CatalogueKeys.RESULTS_ID, CatalogueKeys.Results, ScopeLevel.Window),
        Triple(CatalogueKeys.OPEN_PROJECT_ID, CatalogueKeys.OpenProject, ScopeLevel.Window),
        Triple(CatalogueKeys.INSTALL_CHOICE_ID, CatalogueKeys.InstallChoice, ScopeLevel.Window),
        Triple(CatalogueKeys.SET_KIND_ID, CatalogueKeys.SetKind, ScopeLevel.Window),
        Triple(CatalogueKeys.SET_ORIGIN_ID, CatalogueKeys.SetOrigin, ScopeLevel.Window),
        Triple(CatalogueKeys.SET_QUERY_ID, CatalogueKeys.SetQuery, ScopeLevel.Window),
        Triple(CatalogueKeys.SET_SORT_ID, CatalogueKeys.SetSort, ScopeLevel.Window),
        Triple(CatalogueKeys.SET_TARGET_ID, CatalogueKeys.SetTarget, ScopeLevel.Window),
        Triple(CatalogueKeys.AIM_ID, CatalogueKeys.Aim, ScopeLevel.Window),
        Triple(CatalogueKeys.TOGGLE_FILTER_ID, CatalogueKeys.ToggleFilter, ScopeLevel.Window),
        Triple(CatalogueKeys.EXCLUDE_FILTER_ID, CatalogueKeys.ExcludeFilter, ScopeLevel.Window),
        Triple(CatalogueKeys.REMOVE_FILTER_ID, CatalogueKeys.RemoveFilter, ScopeLevel.Window),
        Triple(CatalogueKeys.CLEAR_FILTERS_ID, CatalogueKeys.ClearFilters, ScopeLevel.Window),
        Triple(CatalogueKeys.SEARCH_FOR_ID, CatalogueKeys.SearchFor, ScopeLevel.Window),
        Triple(CatalogueKeys.UNLOCK_ID, CatalogueKeys.Unlock, ScopeLevel.Window),
        Triple(CatalogueKeys.RELOCK_ID, CatalogueKeys.Relock, ScopeLevel.Window),
        Triple(CatalogueKeys.HIDE_INSTALLED_ID, CatalogueKeys.SetHideInstalled, ScopeLevel.Window),
        Triple(CatalogueKeys.SOURCE_OPTION_ID, CatalogueKeys.SetSourceOption, ScopeLevel.Window),
        Triple(CatalogueKeys.TOGGLE_HIDE_INSTALLED_ID, CatalogueKeys.ToggleHideInstalled, ScopeLevel.Window),
        Triple(CatalogueKeys.TOGGLE_SOURCE_OPTION_ID, CatalogueKeys.ToggleSourceOption, ScopeLevel.Window),
        Triple(CatalogueKeys.MORE_RESULTS_ID, CatalogueKeys.MoreResults, ScopeLevel.Window),
        Triple(CatalogueKeys.RETRY_RESULTS_ID, CatalogueKeys.RetryResults, ScopeLevel.Window),
        Triple(CatalogueKeys.SHOW_RESULTS_ID, CatalogueKeys.ShowResults, ScopeLevel.Window),
        Triple(CatalogueKeys.HIDE_RESULTS_ID, CatalogueKeys.HideResults, ScopeLevel.Window),
        Triple(CatalogueKeys.SCROLL_RESULTS_ID, CatalogueKeys.ScrollResults, ScopeLevel.Window),
        Triple(CatalogueKeys.INSTALL_RESULT_ID, CatalogueKeys.InstallResult, ScopeLevel.Window),
        Triple(CatalogueKeys.CHOOSE_INSTALL_ID, CatalogueKeys.ChooseInstall, ScopeLevel.Window),
        Triple(CatalogueKeys.INSTALL_INTO_ID, CatalogueKeys.InstallInto, ScopeLevel.Window),
        Triple(CatalogueKeys.INSTALL_NEW_PACK_ID, CatalogueKeys.InstallNewPack, ScopeLevel.Window),
        Triple(CatalogueKeys.RETRY_CHOICE_ID, CatalogueKeys.RetryChoice, ScopeLevel.Window),
        Triple(CatalogueKeys.DISMISS_INSTALL_ID, CatalogueKeys.DismissInstall, ScopeLevel.Window),
        Triple(CatalogueKeys.PROJECT_ID, CatalogueKeys.ProjectPage, ScopeLevel.Page),
        Triple(CatalogueKeys.BUILD_ID, CatalogueKeys.BuildPage, ScopeLevel.Page),
        Triple(CatalogueKeys.PACK_ID, CatalogueKeys.PackPage, ScopeLevel.Page),
        Triple(CatalogueKeys.SHOW_PROJECT_ID, CatalogueKeys.ShowProject, ScopeLevel.Page),
        Triple(CatalogueKeys.HIDE_PROJECT_ID, CatalogueKeys.HideProject, ScopeLevel.Page),
        Triple(CatalogueKeys.RELOAD_PROJECT_ID, CatalogueKeys.ReloadProject, ScopeLevel.Page),
        Triple(CatalogueKeys.LOAD_VERSIONS_ID, CatalogueKeys.LoadVersions, ScopeLevel.Page),
        Triple(CatalogueKeys.RETRY_VERSIONS_ID, CatalogueKeys.RetryVersions, ScopeLevel.Page),
        Triple(CatalogueKeys.INSTALL_PROJECT_ID, CatalogueKeys.InstallProject, ScopeLevel.Page),
        Triple(CatalogueKeys.RETRY_INSTALL_ID, CatalogueKeys.RetryInstall, ScopeLevel.Page),
        Triple(CatalogueKeys.SHOW_BUILD_ID, CatalogueKeys.ShowBuild, ScopeLevel.Page),
        Triple(CatalogueKeys.SHOW_PACK_ID, CatalogueKeys.ShowPack, ScopeLevel.Page),
        Triple(CatalogueKeys.HIDE_PACK_ID, CatalogueKeys.HidePack, ScopeLevel.Page),
        Triple(CatalogueKeys.RETRY_PACK_ID, CatalogueKeys.RetryPack, ScopeLevel.Page),
    )

    @Test
    fun `every typed key carries the id its constant names, at the level it is documented at`() {
        keys.forEach { (id, key, level) ->
            val (keyId, keyLevel) = when (key) {
                is SourceKey<*> -> key.id to key.level
                is CommandKey<*> -> key.id to key.level
                else -> error("not a key: $key")
            }
            assertEquals(id, keyId)
            assertEquals(level, keyLevel, id)
        }
        val sources = keys.filter { it.second is SourceKey<*> }.map { it.first }
        val commands = keys.filter { it.second is CommandKey<*> }.map { it.first }
        assertEquals(sources.size, sources.toSet().size, "two sources share an id")
        assertEquals(commands.size, commands.toSet().size, "two commands share an id")
    }

    @Test
    fun `the rail's key keeps the id the launcher shipped before the module existed`() {
        // A layout names it, so a rename would leave the rail's project blocks reading nothing.
        assertEquals("mod.open", CatalogueKeys.OPEN_PROJECT_ID)
    }

    @Test
    fun `a target's key is what a history entry and a trail remember it by`() {
        assertEquals("catalogue:sodium:p1", ProjectTarget.Catalogue("sodium", "p1").key)
        assertEquals("catalogue:sodium:", ProjectTarget.Catalogue("sodium").key)
        assertEquals("installed:p1:Mod:sodium.jar", ProjectTarget.Installed("p1", ProjectKind.Mod, "sodium.jar").key)
        assertEquals("p1", ProjectTarget.Installed("p1", ProjectKind.ShaderPack, "a.zip").packId)
    }

    @Test
    fun `a kind's project type and a search's type are the catalogue's own words`() {
        assertEquals(listOf("mod", "resourcepack", "shader"), ProjectKind.entries.map { it.projectType })
        assertEquals("modpack", catalogueTypeOf(null, ProjectTypes.MODPACK))
        assertEquals(null, catalogueTypeOf(null, null))
        assertEquals("shader", catalogueTypeOf(ProjectKind.ShaderPack, null))
    }

    @Test
    fun `the type of a pack search is the one its source declares`() {
        val listed = SourceView("Listed", "listed.module", "name")
        val searched = SourceView("Searched", "searched.module", "name", packType = ProjectTypes.MODPACK)
        assertEquals(null, BrowseView(origin = "Listed", sources = listOf(listed, searched)).type)
        assertEquals("modpack", BrowseView(origin = "Searched", sources = listOf(listed, searched)).type)
        assertEquals(null, BrowseView(origin = "Gone", sources = listOf(listed, searched)).type)
    }

    @Test
    fun `an option switched off hides the cards of its own source that fall under it, and nothing else`() {
        val view = BrowseView(hidden = setOf(SourceOption("Shelf", "builtBy", "own")))
        assertFalse(view.shows(CatalogueCard("Shelf", "own", "Own", "", facets = mapOf("builtBy" to "own"))))
        assertTrue(view.shows(CatalogueCard("Shelf", "theirs", "Theirs", "", facets = mapOf("builtBy" to "community"))))
        assertTrue(view.shows(CatalogueCard("Shelf", "plain", "Plain", "")), "a card that names no option is listed")
        assertTrue(view.shows(CatalogueCard("Other", "own", "Own", "", facets = mapOf("builtBy" to "own"))), "another source's option of the same name is its own")
        assertFalse(view.lists(SourceOption("Shelf", "builtBy", "own")))
        assertTrue(view.lists(SourceOption("Shelf", "builtBy", "community")))
    }

    private val all = listOf(
        GameVersionTag("1.21.4", GameVersionTag.RELEASE, date = "2024-12-03"),
        GameVersionTag("1.21.3", GameVersionTag.RELEASE),
        GameVersionTag("1.21.2", GameVersionTag.RELEASE),
        GameVersionTag("1.21.1", GameVersionTag.RELEASE),
        GameVersionTag("1.21", GameVersionTag.RELEASE),
        GameVersionTag("1.20.6", GameVersionTag.RELEASE),
        GameVersionTag("1.20.5", GameVersionTag.RELEASE),
    )

    @Test
    fun `game versions fold by major, a whole major as x and a part of one as a range`() {
        assertEquals(listOf("1.21.x"), foldGameVersions(listOf("1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4"), all))
        // Every 1.20 the list publishes is supported, so that major is whole however short it is here.
        assertEquals(listOf("1.21.1-1.21.3", "1.20.x"), foldGameVersions(listOf("1.21.1", "1.21.2", "1.21.3", "1.20.5", "1.20.6"), all))
        assertEquals(listOf("1.21"), foldGameVersions(listOf("1.21"), all))
    }

    @Test
    fun `without the catalogue's list each version is a chip of its own`() {
        assertEquals(listOf(GameVersionGroup("1.21.1", listOf("1.21.1"))), gameVersionChips(listOf("1.21.1"), emptyList()))
    }

    @Test
    fun `a pack's build with no choice made is the one the source points at, else the newest`() {
        val newest = ProjectBuild("b2", "2", "2", "release", emptyList(), emptyList(), "")
        val pointed = ProjectBuild("b1", "1", "1", "beta", emptyList(), emptyList(), "")
        assertEquals(pointed, PackDetails("Shelf", "p", "P", "", versions = listOf(newest, pointed), latestVersionId = "b1").latest)
        assertEquals(newest, PackDetails("Shelf", "p", "P", "", versions = listOf(newest, pointed)).latest)
    }
}
