package hivens.launcher.legacy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which top-level names follow a client's content into an instance.
 *
 * The skip list is the whole of the adoption's judgement and it is a pure
 * function, so it is pinned on its own rather than through a filesystem. The two
 * that matter: a per-version libraries root, which the shared provisioner fills
 * properly, and `bin` / `lib`, which on a modern client are a bundled Windows JRE
 * -- most of why one live client is two gigabytes, and exactly the thing an
 * instance must not inherit from a launcher that provisions its own Java.
 */
class RetiredClientAdopterTest {

    private fun skipped(name: String) = RetiredClientAdopter.isRuntimeArtefact(name)

    @Test
    fun `the unpacked and archived vanilla runtime stays behind`() {
        listOf("assets", "libraries", "versions", "natives", "runtime")
            .forEach { assertTrue(skipped(it), "$it is the launcher's to provision") }
        listOf("assets.zip", "natives.zip", "extra.zip")
            .forEach { assertTrue(skipped(it), "$it is a runtime archive") }
    }

    @Test
    fun `per-version roots and archives stay behind whatever the version`() {
        listOf("libraries-1.12.2", "natives-1.7.10", "assets-1.21.1.zip", "natives-1.12.2.zip")
            .forEach { assertTrue(skipped(it), "$it is version-scoped runtime") }
    }

    /** The case worth two gigabytes: `bin` and `lib` are a bundled JRE, not content. */
    @Test
    fun `a bundled java runtime stays behind`() {
        assertTrue(skipped("bin"))
        assertTrue(skipped("lib"))
    }

    @Test
    fun `logs and caches stay behind`() {
        listOf("logs", "crash-reports", "cache", "downloads", ".fabric", ".mixin.out", ".quilt", "fabricloader.log")
            .forEach { assertTrue(skipped(it), "$it is not the player's content") }
    }

    @Test
    fun `everything the player would miss comes along`() {
        listOf(
            "mods", "config", "saves", "resourcepacks", "shaderpacks", "schematics",
            "options.txt", "servers.dat", "customnpcs", "defaultconfigs", "journeymap",
            "XaeroWaypoints_BACKUP240807",
        ).forEach { assertFalse(skipped(it), "$it is content and must follow") }
    }

    /** A name that merely starts like a runtime one is content. */
    @Test
    fun `the match is the whole name and not a prefix`() {
        assertFalse(skipped("libraries-of-alexandria"))
        assertFalse(skipped("assets-backup"))
        assertFalse(skipped("binder"))
        assertFalse(skipped("library"))
    }

    @Test
    fun `the match ignores case, since one of these trees came off Windows`() {
        assertTrue(skipped("Assets"))
        assertTrue(skipped("BIN"))
        assertTrue(skipped("Libraries-1.12.2"))
    }
}
