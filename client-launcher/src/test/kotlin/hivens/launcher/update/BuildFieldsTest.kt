package hivens.launcher.update

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.ContentToggle
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildFieldsTest {

    private val snapshot = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Mirror, "pack", "5"),
        displayName = "Before",
        instanceDirName = "inst",
        createdAtEpoch = 0L,
        pinnedPackVersion = "5",
        installedBuildKey = "build-5",
        cachedManifest = CachedManifestSnapshot("1.12.2", "forge", "14.23.5.2860", 8),
        optionalContent = listOf(ContentToggle("dropped-by-6", enabled = true), ContentToggle("shared", enabled = true)),
    )

    private val now = snapshot.copy(
        packRef = snapshot.packRef.copy(version = "6"),
        displayName = "Renamed",
        notes = "written after the update",
        playtimeSeconds = 7_200,
        lastPlayedEpochOrZero = 99,
        pinnedPackVersion = "6",
        installedBuildKey = "build-6",
        cachedManifest = CachedManifestSnapshot("1.20.1", "forge", "47.2.0", 17),
        runtime = InstanceRuntime(memoryMb = 6_144, fixedMemory = true),
        optionalContent = listOf(ContentToggle("shared", enabled = false), ContentToggle("new-in-6", enabled = true)),
    )

    @Test
    fun `the build comes from the snapshot and everything else stays`() {
        val rolled = now.withBuildOf(snapshot)

        assertEquals("5", rolled.pinnedPackVersion)
        assertEquals("5", rolled.packRef.version)
        assertEquals("build-5", rolled.installedBuildKey)
        assertEquals(snapshot.cachedManifest, rolled.cachedManifest)
        assertEquals("Renamed", rolled.displayName)
        assertEquals("written after the update", rolled.notes)
        assertEquals(7_200, rolled.playtimeSeconds)
        assertEquals(99, rolled.lastPlayedEpochOrZero)
        assertEquals(now.runtime, rolled.runtime)
    }

    @Test
    fun `a toggle switched since wins, and one the undone build dropped comes back`() {
        val toggles = now.withBuildOf(snapshot).optionalContent.associate { it.entryId to it.enabled }

        assertEquals(false, toggles["shared"], "the player's later choice")
        assertEquals(true, toggles["dropped-by-6"], "the rolled back build has this mod again")
        assertEquals(true, toggles["new-in-6"], "kept, harmless for a build that does not name it")
    }
}
