package hivens.core.jvm

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemMemoryTest {

    /**
     * On a normal JDK (jdk.management present) the read returns the real host RAM, not the
     * [SystemMemory.FALLBACK_MB] fallback, and agrees with the typed com.sun bean. The range
     * check is an independent sanity bound that catches a gross unit error in the production
     * read; the missing-module case a unit test can't reach is covered by the build-time
     * verifyRuntimeModules guard in client-ui.
     */
    @Test
    fun `read returns real host RAM, not the fallback`() {
        val bean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val expectedMb = (bean.totalMemorySize / (1024 * 1024)).toInt()
        val mb = SystemMemory.totalPhysicalMb()
        assertTrue(mb in 256..(8 * 1024 * 1024), "implausible RAM read: $mb MB")
        assertEquals(expectedMb, mb)
        assertEquals(expectedMb, SystemMemory.totalPhysicalMbOrNull())
    }
}
