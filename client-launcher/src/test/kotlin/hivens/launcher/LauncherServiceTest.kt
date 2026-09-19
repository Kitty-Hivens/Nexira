package hivens.launcher

import hivens.launcher.LauncherService.Companion.adaptiveApplies
import hivens.launcher.LauncherService.Companion.baselineMemory
import hivens.launcher.LauncherService.Companion.findAuthlibLibrary
import hivens.launcher.LauncherService.Companion.normalizeMemory
import hivens.launcher.LauncherService.Companion.swapAuthlibPath
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Probe-lite scope: verifies the policies extracted from [LauncherService] into
 * its internal companion (memory tiering + the authlib swap). Both live as
 * companion functions specifically so tests can hit them without having to
 * construct the full collaborator graph or spawn a real process.
 */
class LauncherServiceTest {

    // ── normalizeMemory ──────────────────────────────────────────────────────

    @Test
    fun `normalizeMemory bumps a pin below the modded floor up to 1024`() {
        assertEquals(1024, normalizeMemory(512))
        assertEquals(1024, normalizeMemory(256))
        assertEquals(1024, normalizeMemory(0))
    }

    @Test
    fun `normalizeMemory leaves a usable pin alone`() {
        assertEquals(2048, normalizeMemory(2048))
        assertEquals(8192, normalizeMemory(8192))
        assertEquals(768, normalizeMemory(768))
    }

    // ── adaptiveApplies (the per-instance gate) ──────────────────────────────

    @Test
    fun `adaptiveApplies needs the global signal on and the instance unpinned`() {
        assertTrue(adaptiveApplies(adaptiveEnabled = true, fixedMemory = false))   // default: adaptive on
        assertFalse(adaptiveApplies(adaptiveEnabled = true, fixedMemory = true))   // pinned -> off
        assertFalse(adaptiveApplies(adaptiveEnabled = false, fixedMemory = false)) // global off -> off
        assertFalse(adaptiveApplies(adaptiveEnabled = false, fixedMemory = true))  // both off
    }

    // ── baselineMemory (tier resolution) ─────────────────────────────────────

    @Test
    fun `baselineMemory honours an explicit pin, uncapped`() {
        // Fixed: a deliberate value is respected even above 75% of a small machine.
        assertEquals(6144, baselineMemory(fixedMemory = true, profileMb = 6144, systemRamMb = 4096))
    }

    @Test
    fun `baselineMemory still floors a tiny pin`() {
        assertEquals(1024, baselineMemory(fixedMemory = true, profileMb = 256, systemRamMb = 16384))
    }

    @Test
    fun `unpinned ignores the stored value and uses the Automatic baseline`() {
        // The cold-start over-allocation regression: a 4 GB box no longer gets 6144.
        assertEquals(2457, baselineMemory(fixedMemory = false, profileMb = 6144, systemRamMb = 4096))
    }

    @Test
    fun `unpinned scales the Automatic baseline with the machine`() {
        assertEquals(9830, baselineMemory(fixedMemory = false, profileMb = 4096, systemRamMb = 16384))
    }

    // ── SC-bound authlib swap (the pure half of applySmrtBinding) ─────────────

    private fun runtimeOf(libs: List<ResolvedLibrary>) = ResolvedRuntime(
        libraries = libs,
        clientJar = Path.of("/libs/client.jar"),
        mainClass = "Main",
        assetIndexId = "1.12",
    )

    private val lwjgl = ResolvedLibrary(MavenCoord("org.lwjgl", "lwjgl", "3.3.1"), Path.of("/libs/lwjgl.jar"))
    private val authlib = ResolvedLibrary(MavenCoord("com.mojang", "authlib", "1.5.25"), Path.of("/libs/authlib-1.5.25.jar"))

    @Test
    fun `findAuthlibLibrary picks the com_mojang authlib entry`() {
        assertEquals(authlib, findAuthlibLibrary(runtimeOf(listOf(lwjgl, authlib))))
    }

    @Test
    fun `findAuthlibLibrary returns null when no authlib is present`() {
        assertEquals(null, findAuthlibLibrary(runtimeOf(listOf(lwjgl))))
    }

    @Test
    fun `swapAuthlibPath repoints only the authlib entry`() {
        val patched = Path.of("/cache/smrt-authlib/Industrial/authlib-1.5.25.jar")
        val out = swapAuthlibPath(runtimeOf(listOf(lwjgl, authlib)), authlib, patched)

        assertEquals(patched, out.libraries.first { it.coord.artifact == "authlib" }.path, "authlib path is swapped")
        assertEquals(lwjgl.path, out.libraries.first { it.coord.artifact == "lwjgl" }.path, "other libraries untouched")
        assertEquals(2, out.libraries.size, "no entry added or dropped")
    }
}
