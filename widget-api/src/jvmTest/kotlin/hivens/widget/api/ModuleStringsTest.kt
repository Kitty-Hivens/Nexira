package hivens.widget.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** The order a module's words are looked up in, and what a key nobody translated shows. */
class ModuleStringsTest {

    private val strings = ModuleStrings(
        mapOf(
            "en" to mapOf("hello" to "Hello", "only.en" to "English only", "sum" to "{0} and {1}"),
            "ru" to mapOf("hello" to "Привет"),
        ),
    )

    @Test
    fun `the person's language comes first`() {
        assertEquals("Привет", strings.get("hello", "ru"))
    }

    @Test
    fun `a key the language lacks falls back to English`() {
        assertEquals("English only", strings.get("only.en", "ru"))
    }

    @Test
    fun `a language the module has no file for is read in English`() {
        assertEquals("Hello", strings.get("hello", "ja"))
    }

    @Test
    fun `a key nobody has is shown as itself, so a module with no files shows plain text`() {
        assertEquals("Plain label", strings.get("Plain label", "ru"))
        assertEquals("Plain label", ModuleStrings.EMPTY.get("Plain label", "en"))
    }

    @Test
    fun `numbered places are filled in order`() {
        assertEquals("1 and two", strings.get("sum", "en", 1, "two"))
    }
}
