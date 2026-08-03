package hivens.update

import hivens.core.platform.OS
import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateApplicatorsTest {

    @Test
    fun `factory returns the applicator matching the host OS`() {
        val applicator = UpdateApplicators.forCurrentPlatform()
        when {
            OS.isWindows -> assertTrue(applicator is WindowsUpdateApplicator,
                "Windows host must yield WindowsUpdateApplicator, got ${applicator::class.simpleName}")
            OS.isMacOS   -> assertTrue(applicator is MacUpdateApplicator,
                "macOS host must yield MacUpdateApplicator, got ${applicator::class.simpleName}")
            OS.isLinux   -> assertTrue(applicator is LinuxUpdateApplicator,
                "Linux host must yield LinuxUpdateApplicator, got ${applicator::class.simpleName}")
            else         -> assertTrue(applicator is NoOpUpdateApplicator,
                "Unknown host must yield NoOpUpdateApplicator, got ${applicator::class.simpleName}")
        }
    }
}
