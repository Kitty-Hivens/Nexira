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
}
