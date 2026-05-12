package hivens.launcher

import hivens.core.api.interfaces.IJavaManager
import hivens.core.data.InstanceProfile
import hivens.launcher.LauncherService.Companion.normalizeMemory
import hivens.launcher.LauncherService.Companion.resolveJavaPath
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Probe-lite scope: verifies the policies extracted from [LauncherService] into
 * its internal companion (memory normalisation + Java-path resolution). Both
 * live as companion functions specifically so tests can hit them without
 * having to construct the full collaborator graph or spawn a real process.
 *
 * Java-path resolution depends on [IJavaManager] which is now an interface
 * (Test-harness chunk) — the fake below replaces the real download path.
 */
class LauncherServiceTest {

    // ── normalizeMemory ──────────────────────────────────────────────────────

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

    // ── resolveJavaPath ──────────────────────────────────────────────────────

    private class FakeJavaManager(
        private val result: Path? = null,
        private val throws: Boolean = false
    ) : IJavaManager {
        override suspend fun getJavaPath(version: String): Path =
            if (throws) throw IOException("simulated download failure")
            else result ?: throw IOException("not available")
    }

    @Test
    fun `resolveJavaPath honours profile path when set, never touching managed Java`() = runTest {
        val profile = InstanceProfile(javaPath = "/custom/java/bin/java")
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = profile,
            defaultPath = Path.of("/never/used"),
            version = "1.21.1"
        )
        assertEquals("/custom/java/bin/java", resolved)
    }

    @Test
    fun `resolveJavaPath returns managed Java when on disk`() = runTest {
        val tmp = makeTempDir()
        val managed = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(result = managed),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = Path.of("/never/used"),
            version = "1.21.1"
        )
        assertEquals(managed.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath falls back to defaultPath when manager throws and default exists`() = runTest {
        val tmp = makeTempDir()
        val default = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = default,
            version = "1.21.1"
        )
        assertEquals(default.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath falls back to defaultPath when managed path doesn't exist`() = runTest {
        val tmp = makeTempDir()
        val default = (tmp / "java").also { Files.createFile(it) }
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(result = tmp / "ghost"),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = default,
            version = "1.21.1"
        )
        assertEquals(default.toString(), resolved)
    }

    @Test
    fun `resolveJavaPath returns plain 'java' when nothing else works`() = runTest {
        val tmp = makeTempDir()
        val resolved = resolveJavaPath(
            javaManager = FakeJavaManager(throws = true),
            profile = InstanceProfile(javaPath = ""),
            defaultPath = tmp / "ghost",
            version = "1.21.1"
        )
        assertEquals("java", resolved)
    }

    private fun makeTempDir(): Path = Files.createTempDirectory("launcher-test-").also { it.createDirectories() }
}
