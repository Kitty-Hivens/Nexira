package hivens.core.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemHardwareTest {

    @Test
    fun `physical cores counts distinct physical-id core-id pairs`() {
        // 1 socket, 2 cores, hyper-threaded: 4 logical -> 2 physical.
        val cpuinfo = """
            processor	: 0
            physical id	: 0
            core id		: 0
            cpu cores	: 2

            processor	: 1
            physical id	: 0
            core id		: 1
            cpu cores	: 2

            processor	: 2
            physical id	: 0
            core id		: 0
            cpu cores	: 2

            processor	: 3
            physical id	: 0
            core id		: 1
            cpu cores	: 2
        """.trimIndent()
        assertEquals(2, SystemHardware.physicalCoresFromCpuinfo(cpuinfo))
    }

    @Test
    fun `physical cores counts across two sockets`() {
        // 2 sockets x 1 core each, no HT -> 2 physical.
        val cpuinfo = """
            processor	: 0
            physical id	: 0
            core id		: 0

            processor	: 1
            physical id	: 1
            core id		: 0
        """.trimIndent()
        assertEquals(2, SystemHardware.physicalCoresFromCpuinfo(cpuinfo))
    }

    @Test
    fun `physical cores falls back to cpu cores when ids are absent`() {
        val cpuinfo = """
            processor	: 0
            cpu cores	: 8

            processor	: 1
            cpu cores	: 8
        """.trimIndent()
        assertEquals(8, SystemHardware.physicalCoresFromCpuinfo(cpuinfo))
    }

    @Test
    fun `physical cores is null with no topology info`() {
        assertNull(SystemHardware.physicalCoresFromCpuinfo("processor\t: 0\nvendor_id\t: X\n"))
    }

    @Test
    fun `swap total sums all rows, KB to MB, including zram`() {
        // 8 GiB disk swap + 16 GiB zram, both in KB. Sum = 24576 MB.
        val swaps = """
            Filename				Type		Size		Used		Priority
            /dev/sda2                               partition	8388608		0		-2
            /dev/zram0                              partition	16777216	0		100
        """.trimIndent()
        assertEquals(24576, SystemHardware.swapTotalMbFromProcSwaps(swaps))
    }

    @Test
    fun `swap total is null when only the header is present`() {
        assertNull(SystemHardware.swapTotalMbFromProcSwaps("Filename\tType\tSize\tUsed\tPriority\n"))
    }

    @Test
    fun `logical threads matches the runtime`() {
        assertEquals(Runtime.getRuntime().availableProcessors(), SystemHardware.cpu.logicalThreads)
        assertTrue(SystemHardware.cpu.logicalThreads >= 1)
    }
}
