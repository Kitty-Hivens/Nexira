package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens to a settings file that still names the retired `LibraryFirst`
 * home view.
 *
 * Dropping an enum constant that has been persisted is a decode hazard: a plain
 * `@Serializable` enum throws on a name it does not know, and SettingsService
 * catches that by resetting every other setting to defaults. The shared Json is
 * configured with `coerceInputValues` precisely so an unknown value folds to the
 * field's default instead, and the comment there names HomeView as the case it
 * was added for. This asserts that rather than trusting the comment, because the
 * cost of the comment being stale is a user losing the rest of their settings.
 *
 * The decoder mirrors the one built in the launcher's networkModule. It is
 * duplicated rather than shared because client-core has no DI graph, and the
 * flags that matter here are the two named below.
 */
class RetiredHomeViewTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun `a file naming the retired view decodes to the default home`() {
        val stored = """{"homeView":"LibraryFirst","isDarkTheme":false,"doNotDisturb":true}"""
        val decoded = json.decodeFromString<SettingsData>(stored)
        assertEquals(HomeView.New, decoded.homeView)
    }

    @Test
    fun `the rest of the file survives the unknown value`() {
        // The point of the coercion: one retired enum must not cost the user
        // every other setting in the file.
        val stored = """{"homeView":"LibraryFirst","isDarkTheme":false,"doNotDisturb":true}"""
        val decoded = json.decodeFromString<SettingsData>(stored)
        assertEquals(false, decoded.isDarkTheme)
        assertEquals(true, decoded.doNotDisturb)
    }

    @Test
    fun `the views that remain still decode by name`() {
        listOf(HomeView.Classic, HomeView.New).forEach { view ->
            val decoded = json.decodeFromString<SettingsData>("""{"homeView":"${view.name}"}""")
            assertEquals(view, decoded.homeView)
        }
    }
}
