package hivens.core.time

import hivens.test.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockTest {

    @Test
    fun `system clock tracks wall time`() {
        val before = System.currentTimeMillis()
        val t = SystemClock.nowMillis()
        assertTrue(t >= before, "system clock must be monotonic with wall time")
    }

    @Test
    fun `test clock advances and sets deterministically`() {
        val c = TestClock(start = 1_000)
        assertEquals(1_000, c.nowMillis())
        c.advance(500)
        assertEquals(1_500, c.nowMillis())
        c.advance(0)
        assertEquals(1_500, c.nowMillis())
        c.set(0)
        assertEquals(0, c.nowMillis())
    }
}
