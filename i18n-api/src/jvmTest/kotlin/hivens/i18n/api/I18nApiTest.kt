package hivens.i18n.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class I18nApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.i18nApi")) { "the build did not pass nexira.i18nApi" }
        assertEquals(I18nApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `every typed key carries the id its constant names, at the app level`() {
        val pairs = listOf<Pair<String, Any>>(
            I18nKeys.SETTINGS_ID to I18nKeys.Settings,
            I18nKeys.UPDATE_SETTINGS_ID to I18nKeys.UpdateSettings,
            I18nKeys.LANGUAGE_ID to I18nKeys.Language,
            I18nKeys.LANGUAGES_ID to I18nKeys.Languages,
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
    fun `without the language module every key shows as itself, arguments or not`() {
        val strings = Translator.KEYS.strings("anything")
        assertEquals("some.key", strings.text("some.key"))
        assertEquals("some.key", strings.text("some.key", 3))
        assertFalse(strings.has("some.key"))
    }
}
