package hivens.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpacingTest {

    /**
     * The ladder is a rule: steps of 2 up to twelve, steps of 4 up to twenty
     * four, then 32. Pinned because the rule is the whole argument for these
     * rungs and not others, and a rung added by eye is how the previous scale
     * ended up unable to express what the interface does.
     */
    @Test
    fun `the ladder follows its own rule`() {
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 16, 20, 24, 32).map { it.dp }, Spacing.rungs)

        val values = Spacing.rungs.map { it.value.toInt() }
        for ((low, high) in values.zipWithNext()) {
            val step = high - low
            val expected = when {
                high <= 12 -> 2
                high <= 24 -> 4
                else -> 8
            }
            assertEquals(expected, step, "step from $low to $high")
        }
    }

    @Test
    fun `the ladder ascends and repeats nothing`() {
        val values = Spacing.rungs.map { it.value }
        assertEquals(values.sorted(), values)
        assertEquals(values.distinct().size, values.size)
    }

    @Test
    fun `every rung is a whole number of dp`() {
        assertTrue(Spacing.rungs.all { it.value % 1f == 0f }, "a fractional rung would not survive a density override cleanly")
    }
}
