package hivens.i18n

import hivens.widget.api.ModuleStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class ShippedLanguagesTest {

    @Test
    fun `the languages a module compiled in reads its words in are the languages the launcher ships`() {
        assertEquals(ShippedLanguages.tags, ModuleStrings.SHIPPED_LANGUAGES.toSet())
    }

    @Test
    fun `the language module ships its own name in every one of them`() {
        val tables = I18nModule.strings.tables
        assertEquals(ShippedLanguages.tags, tables.keys)
        tables.forEach { (tag, words) -> assertEquals(I18nModule.name, words.keys.single(), tag) }
    }
}
