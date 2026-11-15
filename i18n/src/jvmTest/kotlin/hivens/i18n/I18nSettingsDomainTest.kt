package hivens.i18n

import hivens.i18n.api.I18nSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The language a person chose before the module existed is taken from the launcher's old settings file, and nothing else is. */
class I18nSettingsDomainTest {

    private fun legacy(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the old locale field is the language`() {
        assertEquals(I18nSettings("de"), I18nSettingsDomain.imported(legacy("""{"locale":"de","isDarkTheme":true}""")))
    }

    @Test
    fun `an old file with no locale, or a blank one, imports nothing`() {
        assertNull(I18nSettingsDomain.imported(legacy("""{"isDarkTheme":true}""")))
        assertNull(I18nSettingsDomain.imported(legacy("""{"locale":"  "}""")))
    }

    @Test
    fun `the domain is named for its file and defaults to English`() {
        assertEquals("i18n", I18nSettingsDomain.id)
        assertEquals(I18nSettings("en"), I18nSettingsDomain.defaults())
    }
}
