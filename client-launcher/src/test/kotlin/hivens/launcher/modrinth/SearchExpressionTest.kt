package hivens.launcher.modrinth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The expression the catalogue is searched with, pinned against what its own
 * sidebar sends for the same choices. Each rule here is one a reader can see go
 * wrong in the results, so each is its own case.
 */
class SearchExpressionTest {

    @Test
    fun `nothing chosen is the project type alone`() {
        assertEquals("project_types = `mod`", searchExpression("mod", emptyList()))
    }

    @Test
    fun `versions are any of them, and quoted so they stay strings`() {
        assertEquals(
            "game_versions IN [`1.20.1`, `1.21.1`] AND project_types = `mod`",
            searchExpression("mod", listOf(SearchFilter(FilterField.GameVersion, "1.21.1"), SearchFilter(FilterField.GameVersion, "1.20.1"))),
        )
        assertEquals(
            "game_versions = `1.20` AND project_types = `mod`",
            searchExpression("mod", listOf(SearchFilter(FilterField.GameVersion, "1.20"))),
        )
    }

    @Test
    fun `categories are all of them and loaders any of them`() {
        val expression = searchExpression(
            "mod",
            listOf(
                SearchFilter(FilterField.Category, "optimization"),
                SearchFilter(FilterField.Category, "utility"),
                SearchFilter(FilterField.Loader, "fabric"),
                SearchFilter(FilterField.Loader, "quilt"),
            ),
        )
        assertEquals(
            "categories = `optimization` AND categories = `utility` AND categories IN [`fabric`, `quilt`] AND project_types = `mod`",
            expression,
        )
    }

    @Test
    fun `exclusions on one field fold into one clause`() {
        val expression = searchExpression(
            "mod",
            listOf(
                SearchFilter(FilterField.Loader, "forge", excluded = true),
                SearchFilter(FilterField.Category, "cursed", excluded = true),
                SearchFilter(FilterField.Disclosure, "telemetry", excluded = true),
                SearchFilter(FilterField.Disclosure, "advertisements", excluded = true),
            ),
        )
        assertEquals(
            "categories NOT IN [`forge`, `cursed`] AND disclosure_types NOT IN [`advertisements`, `telemetry`] AND project_types = `mod`",
            expression,
        )
    }

    @Test
    fun `an open licence is a boolean, asked for or ruled out`() {
        assertEquals(
            "open_source = true AND project_types = `shader`",
            searchExpression("shader", listOf(SearchFilter(FilterField.OpenSource, ""))),
        )
        assertEquals(
            "open_source NOT IN [true] AND project_types = `shader`",
            searchExpression("shader", listOf(SearchFilter(FilterField.OpenSource, "", excluded = true))),
        )
    }

    @Test
    fun `a client mod is any that the client can run`() {
        assertEquals(
            "environment IN [`client_only`, `client_only_server_optional`, `client_or_server_prefers_both`, `client_or_server`] AND project_types = `mod`",
            searchExpression("mod", listOf(SearchFilter(FilterField.Environment, ENV_CLIENT))),
        )
    }

    @Test
    fun `asking for both sides is a project with a part on each`() {
        val expression = searchExpression(
            "mod",
            listOf(SearchFilter(FilterField.Environment, ENV_CLIENT), SearchFilter(FilterField.Environment, ENV_SERVER)),
        )
        assertEquals(
            "environment IN [`client_only_server_optional`, `server_only_client_optional`, `client_and_server`, `client_or_server`, `client_or_server_prefers_both`] AND project_types = `mod`",
            expression,
        )
    }

    @Test
    fun `what a pack holds is hidden by id`() {
        assertEquals(
            "project_id NOT IN [`AANobbMI`, `gvQqBUqZ`] AND project_types = `mod`",
            searchExpression(
                "mod",
                listOf(SearchFilter(FilterField.Project, "gvQqBUqZ", excluded = true), SearchFilter(FilterField.Project, "AANobbMI", excluded = true)),
            ),
        )
    }

    @Test
    fun `a project or a disclosure is never asked for, only ruled out`() {
        assertEquals(
            "project_types = `mod`",
            searchExpression("mod", listOf(SearchFilter(FilterField.Project, "x"), SearchFilter(FilterField.Disclosure, "telemetry"))),
        )
    }

    @Test
    fun `a value cannot close its own quotes`() {
        assertEquals(
            "categories = `ab` AND project_types = `mod`",
            searchExpression("mod", listOf(SearchFilter(FilterField.Category, "a`b"))),
        )
    }
}
