package hivens.i18n

import hivens.i18n.api.I18nSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The language a person chose before the module existed is taken from the launcher's old settings file, and
 * nothing else is. A first start speaks the machine's language where the launcher ships it.
 */
class I18nSettingsDomainTest {

    private fun legacy(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the old locale field is the language`() {
        assertEquals(I18nSettings("de"), I18nSettingsDomain.imported(legacy("""{"locale":"de","isDarkTheme":true}"""), current = null))
    }

    @Test
    fun `an old file with no locale, or a blank one, imports nothing`() {
        assertNull(I18nSettingsDomain.imported(legacy("""{"isDarkTheme":true}"""), current = I18nSettings("ja")))
        assertNull(I18nSettingsDomain.imported(legacy("""{"locale":"  "}"""), current = null))
    }

    @Test
    fun `the domain is named for its file and defaults to English`() {
        assertEquals("i18n", I18nSettingsDomain.id)
        assertEquals(I18nSettings("en"), I18nSettingsDomain.defaults())
    }

    @Test
    fun `a first start speaks a shipped system language, regardless of region`() {
        assertEquals("ru", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("ru-RU")))
        assertEquals("ru", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("ru-UA")))
        assertEquals("ru", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("ru-KZ")))
        assertEquals("de", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("de-CH")))
        assertEquals("ja", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("ja-JP")))
        assertEquals("en", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `a first start on a language the launcher does not ship speaks English`() {
        assertEquals("en", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("pl-PL")))
        assertEquals("en", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("uk-UA")))
        assertEquals("en", I18nSettingsDomain.firstLanguage(Locale.forLanguageTag("zh-CN")))
        assertEquals("en", I18nSettingsDomain.firstLanguage(Locale.ROOT))
    }

    @Test
    fun `a first start reads the machine's language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de-AT"))
            assertEquals(I18nSettings("de"), I18nSettingsDomain.firstRun())
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            assertEquals(I18nSettingsDomain.defaults(), I18nSettingsDomain.firstRun(), "English is the default, so nothing is pinned")
        } finally {
            Locale.setDefault(before)
        }
    }
}
