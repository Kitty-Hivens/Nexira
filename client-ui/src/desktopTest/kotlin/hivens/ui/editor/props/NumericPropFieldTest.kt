package hivens.ui.editor.props

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a numeric property field lets through.
 *
 * The rule is a keystroke filter, and a filter is the kind of thing that is
 * either too loose, which is the defect it was written for, or too tight, which
 * traps somebody in the middle of typing a number they are allowed to type. Both
 * directions are asserted, because only one of them shows up by using it.
 */
class NumericPropFieldTest {

    @Test
    fun `an integer field takes digits and nothing else`() {
        for (good in listOf("0", "7", "196", "480")) {
            assertTrue(isNumeric(good, decimals = false), "should take: $good")
        }
        for (bad in listOf("abc", "1a", "a1", "1.5", "1 ", " 1", "1,5", "٣", "1e3")) {
            assertFalse(isNumeric(bad, decimals = false), "should refuse: $bad")
        }
    }

    @Test
    fun `a field being typed into is not yet wrong`() {
        // Both are on the way to a number and neither is one, so the keystroke is
        // taken and nothing is reported until it resolves. A filter that refused
        // these would make a field impossible to clear and a negative impossible
        // to start.
        assertTrue(isNumeric("", decimals = false))
        assertTrue(isNumeric("-", decimals = false))
        assertTrue(isNumeric("", decimals = true))
        assertTrue(isNumeric("-", decimals = true))
    }

    @Test
    fun `a decimal field takes one point and only one`() {
        for (good in listOf("0.5", "-2.25", ".5", "3.", "12")) {
            assertTrue(isNumeric(good, decimals = true), "should take: $good")
        }
        for (bad in listOf("1.2.3", "..", "1..2")) {
            assertFalse(isNumeric(bad, decimals = true), "should refuse: $bad")
        }
    }

    @Test
    fun `a decimal point is not a digit`() {
        assertFalse(isNumeric("0.5", decimals = false), "an integer field has no point in it")
    }

    @Test
    fun `a sign belongs at the front or nowhere`() {
        assertTrue(isNumeric("-196", decimals = false))
        assertFalse(isNumeric("19-6", decimals = false))
        assertFalse(isNumeric("196-", decimals = false))
        assertFalse(isNumeric("--1", decimals = false))
    }
}
