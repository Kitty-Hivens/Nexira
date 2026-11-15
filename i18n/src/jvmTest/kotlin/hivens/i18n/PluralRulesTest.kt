package hivens.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The plural families, against the agreement each language actually makes. */
class PluralRulesTest {

    private fun forms(family: String, vararg n: Long) = n.map { PluralRules.of(family).categoryOf(it) }

    @Test
    fun `east slavic takes few for 2 to 4 and many for 11 to 14, so 1 file but 11 files`() {
        assertEquals(
            listOf("one", "few", "many", "many", "many", "one", "few", "many", "many", "many"),
            forms("east-slavic", 1, 2, 5, 11, 12, 21, 22, 111, 112, 0),
        )
    }

    @Test
    fun `one-other has a single break at one`() {
        assertEquals(listOf("other", "one", "other", "other"), forms("one-other", 0, 1, 2, 21))
    }

    @Test
    fun `other knows one form`() {
        assertEquals(listOf("other", "other"), forms("other", 1, 2))
    }

    @Test
    fun `the remaining families follow their languages`() {
        assertEquals(listOf("one", "one", "other"), forms("french", 0, 1, 2))
        assertEquals(listOf("one", "few", "other"), forms("west-slavic", 1, 3, 5))
        assertEquals(listOf("one", "few", "many", "many", "few"), forms("polish", 1, 2, 5, 12, 22))
    }

    @Test
    fun `a family nobody knows reads as other only`() {
        assertNull(PluralRules.find("klingon"))
        assertEquals(listOf("other", "other"), forms("klingon", 1, 2))
    }
}
