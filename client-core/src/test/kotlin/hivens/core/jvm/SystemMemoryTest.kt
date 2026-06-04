package hivens.core.jvm

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemMemoryTest {

    /**
     * On a normal JDK (jdk.management present) the read returns the real host RAM, not the
     * [SystemMemory.FALLBACK_MB] fallback. Mirrors the production computation against the
     * typed com.sun bean -- guards the MB conversion and the real-vs-fallback distinction.
     * The missing-module case a unit test can't reach is covered by the build-time
     * verifyRuntimeModules guard in client-ui.
     */
    @Test
    fun `read matches the typed com_sun bean`() {
        val bean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val expectedMb = (bean.totalMemorySize / (1024 * 1024)).toInt()
        assertTrue(expectedMb > 0, "test precondition: a full JDK should report positive RAM")
        assertEquals(expectedMb, SystemMemory.totalPhysicalMb())
    }
}
