package hivens.core.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeapDeriverTest {

    @Test
    fun `applies headroom over the live set`() {
        // 2048 live * 1.5 = 3072, within [1024, 16384*0.75]
        assertEquals(3072, HeapDeriver.derive(liveSetMb = 2048, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `clamps up to the floor when the live set is tiny`() {
        assertEquals(1024, HeapDeriver.derive(liveSetMb = 200, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `clamps down to 75 percent of machine RAM when the live set is huge`() {
        // 8000 * 1.5 = 12000 would exceed 8192*0.75 = 6144
        assertEquals(6144, HeapDeriver.derive(liveSetMb = 8000, machineRamMb = 8192, floorMb = 1024))
    }

    @Test
    fun `degenerate machine RAM never yields below the floor`() {
        // ceiling 0*0.75 = 0 is below the floor; the floor wins
        assertEquals(1024, HeapDeriver.derive(liveSetMb = 4000, machineRamMb = 0, floorMb = 1024))
    }

    @Test
    fun `rolling max uses the largest reliable sample`() {
        // peak 3000 * 1.5 = 4500
        assertEquals(4500, HeapDeriver.derive(listOf(1200, 3000, 800), machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `rolling max ignores zero and negative samples`() {
        assertEquals(3000, HeapDeriver.derive(listOf(0, -5, 2000), machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `rolling max returns null when no usable samples`() {
        assertNull(HeapDeriver.derive(emptyList(), machineRamMb = 16384, floorMb = 1024))
        assertNull(HeapDeriver.derive(listOf(0, 0), machineRamMb = 16384, floorMb = 1024))
    }
}
