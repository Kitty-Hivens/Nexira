package hivens.launcher

import hivens.launcher.LauncherService.Companion.normalizeMemory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Probe-lite scope: verifies the memory-allocation policy that used to be a few
 * inline `if`s buried in [LauncherService.launchClientWithLogs]. Pulling it into
 * an internal companion function let us reach it from tests without spawning a
 * real process.
 *
 * The Java-path resolution policy is also extracted (see [LauncherService.resolveJavaPath])
 * but its exhaustive testing depends on [JavaManagerService] being mockable, which
 * requires an `IJavaManager` interface — that refactor lives in the upcoming
 * Test-harness chunk so the runtime download path can be faked deterministically.
 */
class LauncherServiceTest {

    @Test
    fun `normalizeMemory bumps tiny allocations up to 1024`() {
        assertEquals(1024, normalizeMemory(profileMb = 0, allocatedMb = 512))
        assertEquals(1024, normalizeMemory(profileMb = 256, allocatedMb = 8192))
        assertEquals(1024, normalizeMemory(profileMb = 0, allocatedMb = 0))
    }

    @Test
    fun `normalizeMemory honours profile when positive`() {
        assertEquals(2048, normalizeMemory(profileMb = 2048, allocatedMb = 4096))
    }

    @Test
    fun `normalizeMemory falls back to allocated when profile is zero`() {
        assertEquals(4096, normalizeMemory(profileMb = 0, allocatedMb = 4096))
    }

    @Test
    fun `normalizeMemory leaves comfortable values alone`() {
        assertEquals(8192, normalizeMemory(profileMb = 0, allocatedMb = 8192))
        assertEquals(768, normalizeMemory(profileMb = 768, allocatedMb = 0))
    }
}
