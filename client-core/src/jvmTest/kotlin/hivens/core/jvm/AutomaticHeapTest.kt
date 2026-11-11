package hivens.core.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomaticHeapTest {

    @Test
    fun `scales at 60 percent of RAM in the common range`() {
        assertEquals(4915, AutomaticHeap.compute(8192))   // 0.6 * 8192
        assertEquals(9830, AutomaticHeap.compute(16384))  // 0.6 * 16384
    }

    @Test
    fun `caps at the upper bound on big machines`() {
        assertEquals(10240, AutomaticHeap.compute(32768)) // 0.6*32768 = 19660 -> capped 10240
        assertEquals(10240, AutomaticHeap.compute(65536)) // 64 GB -> still 10240
    }

    @Test
    fun `never exceeds 75 percent of host RAM for machines that can honour it`() {
        // The floor (1024) deliberately overrides the ceiling on sub-1.4 GB hosts, so
        // this invariant is asserted only for >= 2 GB, where 0.6*RAM already clears 1024.
        for (ram in listOf(2048, 3072, 4096, 6144, 8192, 12288, 16384, 32768)) {
            assertTrue(
                AutomaticHeap.compute(ram) <= (ram * 0.75).toInt(),
                "compute($ram) must not exceed 75% of host RAM",
            )
        }
    }

    @Test
    fun `floors at 1 GB on small machines`() {
        assertEquals(1024, AutomaticHeap.compute(1024)) // 0.6*1024 = 614 -> floor 1024
        assertEquals(1024, AutomaticHeap.compute(512))
    }

    @Test
    fun `degenerate zero RAM yields the floor, not zero`() {
        assertEquals(1024, AutomaticHeap.compute(0))
    }

    @Test
    fun `the 4 GB machine gets a capped baseline, not the old static default`() {
        // The bug this tier fixes: a 4 GB box must not get 6144. 0.6*4096 = 2457, under
        // 0.75*4096 = 3072.
        assertEquals(2457, AutomaticHeap.compute(4096))
    }
}
