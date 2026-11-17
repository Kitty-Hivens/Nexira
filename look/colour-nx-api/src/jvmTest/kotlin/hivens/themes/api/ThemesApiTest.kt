package hivens.themes.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemesApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.themesApi")) { "the build did not pass nexira.themesApi" }
        assertEquals(ThemesApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `every typed key carries the id its constant names, at the app level`() {
        val pairs = listOf<Pair<String, Any>>(
            ThemesKeys.SETTINGS_ID to ThemesKeys.Settings,
            ThemesKeys.UPDATE_SETTINGS_ID to ThemesKeys.UpdateSettings,
            ThemesKeys.ACTIVE_ID to ThemesKeys.Active,
            ThemesKeys.OFFERED_ID to ThemesKeys.Offered,
            ThemesKeys.DARK_ID to ThemesKeys.Dark,
            ThemesKeys.MODE_ID to ThemesKeys.Mode,
            ThemesKeys.SYSTEM_SCHEME_AVAILABLE_ID to ThemesKeys.SystemSchemeAvailable,
            ThemesKeys.CHOOSE_ID to ThemesKeys.Choose,
            ThemesKeys.SET_DARK_ID to ThemesKeys.SetDark,
            ThemesKeys.SET_MODE_ID to ThemesKeys.SetMode,
        )
        pairs.forEach { (id, key) ->
            val (keyId, level) = when (key) {
                is SourceKey<*> -> key.id to key.level
                is CommandKey<*> -> key.id to key.level
                else -> error("not a key: $key")
            }
            assertEquals(id, keyId)
            assertEquals(ScopeLevel.App, level, id)
        }
        assertEquals(pairs.size, pairs.map { it.first }.toSet().size, "two keys share an id")
    }

    @Test
    fun `nothing chosen yet follows the system and opens dark, as the former settings file did`() {
        assertEquals(ThemeSettings(theme = null, mode = ThemeMode.System, dark = true, copied = null), ThemeSettings())
    }
}
