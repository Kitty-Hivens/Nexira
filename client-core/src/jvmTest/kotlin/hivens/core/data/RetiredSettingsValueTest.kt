package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens to a settings file naming something this build no longer has.
 *
 * Two hazards, and the file on disk hits both as the launcher retires things.
 * A plain `@Serializable` enum throws on a constant it does not know, and a
 * field that has been dropped entirely throws as an unknown key; SettingsService
 * catches either by resetting every other setting to defaults. The shared Json
 * is configured with `coerceInputValues` and `ignoreUnknownKeys` precisely so
 * both fold quietly instead. This asserts that rather than trusting the comment
 * there, because the cost of the comment being stale is a user losing the rest
 * of their settings.
 *
 * `homeView` is the concrete case behind the second half: it was a stored field
 * with a stored enum, and it is gone along with the classic home it selected.
 *
 * The decoder mirrors the one built in the launcher's networkModule. It is
 * duplicated rather than shared because client-core has no DI graph, and the
 * flags that matter here are the two named below.
 */
class RetiredSettingsValueTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun `a file naming a retired enum constant decodes to the field's default`() {
        val stored = """{"themeMode":"Seasonal","isDarkTheme":false,"doNotDisturb":true}"""
        val decoded = json.decodeFromString<SettingsData>(stored)
        assertEquals(SettingsData().themeMode, decoded.themeMode)
    }

    @Test
    fun `a file naming a retired field decodes without it`() {
        val stored = """{"homeView":"Classic","isDarkTheme":false,"doNotDisturb":true}"""
        val decoded = json.decodeFromString<SettingsData>(stored)
        assertEquals(false, decoded.isDarkTheme)
        assertEquals(true, decoded.doNotDisturb)
    }

    @Test
    fun `the rest of the file survives an unknown value`() {
        // The point of the coercion: one retired enum must not cost the user
        // every other setting in the file.
        val stored = """{"themeMode":"Seasonal","isDarkTheme":false,"doNotDisturb":true}"""
        val decoded = json.decodeFromString<SettingsData>(stored)
        assertEquals(false, decoded.isDarkTheme)
        assertEquals(true, decoded.doNotDisturb)
    }

    @Test
    fun `the constants that remain still decode by name`() {
        ThemeMode.entries.forEach { mode ->
            val decoded = json.decodeFromString<SettingsData>("""{"themeMode":"${mode.name}"}""")
            assertEquals(mode, decoded.themeMode)
        }
    }
}
