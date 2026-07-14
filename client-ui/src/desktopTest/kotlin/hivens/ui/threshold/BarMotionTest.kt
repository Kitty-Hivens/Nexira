package hivens.ui.threshold

import kotlin.test.Test
import kotlin.test.assertTrue

class BarMotionTest {

    private fun BarMotion.run(frames: Int, dtMs: Float, target: Float, ceiling: Float, tauMs: Float = 180f): Float {
        var v = 0f
        repeat(frames) { v = tick(dtMs, target, ceiling, tauMs) }
        return v
    }

    @Test
    fun `approaches the stage floor`() {
        val m = BarMotion()
        val v = m.run(frames = 60, dtMs = 16f, target = 0.4f, ceiling = 0.5f)
        assertTrue(v > 0.39f, "expected ~0.4 after 1s, got $v")
    }

    @Test
    fun `never moves backward when target drops`() {
        val m = BarMotion()
        m.run(frames = 60, dtMs = 16f, target = 0.5f, ceiling = 0.6f)
        val before = m.displayed
        val after = m.tick(16f, target = 0.1f, ceiling = 0.6f)
        assertTrue(after >= before, "bar regressed: $before -> $after")
    }

    @Test
    fun `creep crawls past the target but stops at the ceiling`() {
        val m = BarMotion()
        // Long stall on one stage: creep should pass the floor and cap at the ceiling.
        val v = m.run(frames = 4000, dtMs = 16f, target = 0.2f, ceiling = 0.32f)
        assertTrue(v > 0.2f, "creep never engaged: $v")
        assertTrue(v <= 0.32f + 1e-4f, "creep passed the ceiling: $v")
    }

    @Test
    fun `monotonic across a full staged boot`() {
        val m = BarMotion()
        var prev = 0f
        val stages = listOf(
            Triple(0.02f, 0.20f, 30),
            Triple(0.20f, 0.32f, 30),
            Triple(0.32f, 0.40f, 10),
            Triple(0.40f, 0.92f, 120),
        )
        for ((floor, ceiling, frames) in stages) {
            repeat(frames) {
                val v = m.tick(16f, floor, ceiling)
                assertTrue(v >= prev, "regressed at floor=$floor: $prev -> $v")
                prev = v
            }
        }
        // Ready: sweep to full with the fast tau.
        repeat(60) {
            val v = m.tick(16f, 1f, 1f, tauMs = 70f)
            assertTrue(v >= prev)
            prev = v
        }
        assertTrue(prev > 0.995f, "sweep did not reach full: $prev")
    }
}
