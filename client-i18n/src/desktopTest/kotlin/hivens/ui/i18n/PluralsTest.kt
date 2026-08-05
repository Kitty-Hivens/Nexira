package hivens.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class PluralsTest {

    private fun ru(n: Int) = russianPlural(n, "one", "few", "many")

    @Test
    fun `russian one form`() {
        for (n in listOf(1, 21, 31, 101, 1001)) {
            assertEquals("one", ru(n), "n=$n")
        }
    }

    @Test
    fun `russian few form`() {
        for (n in listOf(2, 3, 4, 22, 23, 24, 102, 103)) {
            assertEquals("few", ru(n), "n=$n")
        }
    }

    @Test
    fun `russian many form covers the 11-14 and teens carve-out`() {
        for (n in listOf(0, 5, 11, 12, 13, 14, 15, 20, 25, 100, 111, 1000)) {
            assertEquals("many", ru(n), "n=$n")
        }
    }

    @Test
    fun `two-form breaks only at one`() {
        assertEquals("one", twoFormPlural(1, "one", "other"))
        for (n in listOf(0, 2, 5, 11, 21)) {
            assertEquals("other", twoFormPlural(n, "one", "other"), "n=$n")
        }
    }
}
