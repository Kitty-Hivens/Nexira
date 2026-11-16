package hivens.widget.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a module's words carry to the language module. The lookup order is that module's, and tested there. */
class ModuleStringsTest {

    @Test
    fun `the languages are those of the module's own words`() {
        val strings = ModuleStrings(
            tables = mapOf("en" to mapOf("hello" to "Hello"), "ru" to mapOf("hello" to "Привет")),
            namespaces = mapOf("nexira" to mapOf("pl" to mapOf("navBack" to "Wstecz"))),
        )
        assertEquals(setOf("en", "ru"), strings.languages)
        assertFalse(strings.isEmpty)
    }

    @Test
    fun `words for another namespace alone are not nothing`() {
        assertTrue(ModuleStrings.EMPTY.isEmpty)
        assertFalse(ModuleStrings(namespaces = mapOf("nexira" to mapOf("pl" to mapOf("navBack" to "Wstecz")))).isEmpty)
    }

    @Test
    fun `a module compiled in reads its own words from its resources, the way a jar's files are read`() {
        val strings = ModuleStrings.resources(ModuleStringsTest::class.java, "test.words")
        assertEquals(setOf("en", "ru"), strings.languages, "a language with no file is left out")
        assertEquals("Hello, {0}", strings.tables.getValue("en")["greeting"])
        assertEquals("line\none", strings.tables.getValue("en")["escaped"], "escapes read as a property file's")
        assertEquals("Привет, {0}", strings.tables.getValue("ru")["greeting"], "read as UTF-8")
        assertTrue(ModuleStrings.resources(ModuleStringsTest::class.java, "test.nothing").isEmpty)
    }
}
