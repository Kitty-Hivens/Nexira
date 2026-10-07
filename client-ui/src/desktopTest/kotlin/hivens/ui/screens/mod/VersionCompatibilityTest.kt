package hivens.ui.screens.mod

import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which builds the versions table marks, and which it leaves alone.
 *
 * The rule the whole install path now rests on: the launcher's own pick refuses
 * where nothing runs on the pack, and the table marks the ones a reader may still
 * take on purpose. Both read this, so getting it wrong either hides a working
 * build behind a warning or lets the header install a jar that cannot load.
 */
class VersionCompatibilityTest {

    private fun build(gameVersions: List<String>, loaders: List<String>) = ModrinthVersion(
        id = "v",
        projectId = "p",
        gameVersions = gameVersions,
        loaders = loaders,
        files = listOf(
            ModrinthFile(hashes = ModrinthHashes(sha1 = "0"), url = "u", filename = "f.jar", size = 1),
        ),
    )

    @Test
    fun `a build matching both axes runs`() {
        assertTrue(runsOn(build(listOf("1.21.1"), listOf("neoforge")), "1.21.1", listOf("neoforge")))
    }

    @Test
    fun `a build for another loader does not`() {
        assertFalse(runsOn(build(listOf("1.21.1"), listOf("fabric")), "1.21.1", listOf("neoforge")))
    }

    @Test
    fun `a build for another game version does not`() {
        assertFalse(runsOn(build(listOf("1.20.1"), listOf("neoforge")), "1.21.1", listOf("neoforge")))
    }

    /** Quilt packs read Fabric mods, so the caller passes both and either satisfies it. */
    @Test
    fun `any of the pack's loaders is enough`() {
        assertTrue(runsOn(build(listOf("1.21.1"), listOf("fabric")), "1.21.1", listOf("quilt", "fabric")))
    }

    /**
     * An axis nobody knows fails nothing, and the other one still answers.
     *
     * Per axis, not all-or-nothing: a page opened without a pack behind it must
     * not mark every row, and a pack whose manifest named a loader but no game
     * version still knows enough to rule a Fabric build out of a NeoForge folder.
     */
    @Test
    fun `an unknown axis fails nothing, a known one still does`() {
        // Neither axis known: there is nothing here to be incompatible with.
        assertTrue(runsOn(build(listOf("1.20.1"), listOf("fabric")), "", emptyList()))
        // The known axis matches and the other is not known.
        assertTrue(runsOn(build(listOf("1.21.1"), listOf("fabric")), "1.21.1", emptyList()))
        // The game version is unknown; the loader is known and wrong.
        assertFalse(runsOn(build(listOf("1.20.1"), listOf("fabric")), "", listOf("neoforge")))
        // The loader is unknown; the game version is known and wrong.
        assertFalse(runsOn(build(listOf("1.20.1"), listOf("fabric")), "1.21.1", emptyList()))
    }
}
