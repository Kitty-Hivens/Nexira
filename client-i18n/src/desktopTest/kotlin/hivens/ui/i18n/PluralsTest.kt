package hivens.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shipped tables agree with their numbers the way their languages do. The rule
 * families themselves are tested in the language module. This holds the launcher's own
 * messages to them, the Russian 11 to 14 carve-out above all: 1 and 21 take the one form,
 * 11 to 14 the many form though they end in 1 to 4.
 */
class PluralsTest {

    private fun count(tag: String, n: Int) = NexiraStrings.shipped(tag).text("versionPickerCount", n)

    @Test
    fun `russian one form`() {
        for (n in listOf(1, 21, 31, 101, 1001)) {
            assertEquals("$n версия", count("ru", n), "n=$n")
        }
    }

    @Test
    fun `russian few form`() {
        for (n in listOf(2, 3, 4, 22, 23, 24, 102, 103)) {
            assertEquals("$n версии", count("ru", n), "n=$n")
        }
    }

    @Test
    fun `russian many form covers the 11-14 and teens carve-out`() {
        for (n in listOf(0, 5, 11, 12, 13, 14, 15, 20, 25, 100, 111, 1000)) {
            assertEquals("$n версий", count("ru", n), "n=$n")
        }
    }

    @Test
    fun `two-form breaks only at one`() {
        assertEquals("1 version", count("en", 1))
        for (n in listOf(0, 2, 5, 11, 21)) {
            assertEquals("$n versions", count("en", n), "n=$n")
        }
        assertEquals("1 Version", count("de", 1))
        assertEquals("21 Versionen", count("de", 21))
    }
}
