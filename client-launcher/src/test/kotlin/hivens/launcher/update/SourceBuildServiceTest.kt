package hivens.launcher.update

import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ReleaseChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceBuildServiceTest {

    private val noopApplicator = object : IUpdateApplicator {
        override fun scheduleUpdate(installerPath: Path) {}
    }

    private fun service(onPath: (String) -> Boolean = { true }) =
        SourceBuildService(Files.createTempDirectory("src-build-test"), noopApplicator, onPath)

    @Test
    fun `dev tracks the dev branch, git builds stable`() {
        val svc = service()
        assertEquals("dev", svc.branchFor(ReleaseChannel.Dev))
        assertEquals("stable", svc.branchFor(ReleaseChannel.Git))
    }

    @Test
    fun `gradle args build the appimage jar + profile at the given version`() {
        val args = service().gradleArgs("9.9.9")
        assertTrue(":client-ui:packageReleaseUberJarForCurrentOS" in args)
        assertTrue(":client-ui:emitAppImageProfile" in args)
        assertTrue("-PappVersion=9.9.9" in args)
        assertTrue("--no-daemon" in args)
    }

    @Test
    fun `toolchain is ready only when git + jdk + appimagetool are all present`() {
        // onPath true for all three -> ready (independent of JAVA_HOME).
        assertTrue(service { it in setOf("git", "javac", "appimagetool") }.detectToolchain().ready)
    }

    @Test
    fun `missing tools are reported`() {
        val tc = SourceBuildService.Toolchain(git = true, jdk = false, appImageTool = false)
        assertFalse(tc.ready)
        assertEquals(listOf("JDK (javac)", "appimagetool"), tc.missing)
    }
}
