package hivens.launcher.modrinth

import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.instance.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a build goes, read off the loaders it was published for the way the catalogue reads them. */
class ModrinthPlacementTest {

    private fun into(kind: ContentKind) = Placement.Into(kind)
    private fun refused(reason: PlacementRefusal) = Placement.Refused(reason)

    @Test
    fun `a mod for the pack's loader is a mod`() {
        assertEquals(into(ContentKind.Mod), placementFor(listOf("neoforge"), "neoforge", "1.21.1"))
        assertEquals(into(ContentKind.Mod), placementFor(listOf("fabric", "quilt"), "fabric", "1.21.1"))
    }

    @Test
    fun `a mod that is also a data pack is a mod where its loader runs`() {
        assertEquals(into(ContentKind.Mod), placementFor(listOf("datapack", "fabric"), "fabric", "1.21.1"))
        assertEquals(refused(PlacementRefusal.WrongLoader), placementFor(listOf("datapack", "fabric"), "neoforge", "1.21.1"))
    }

    @Test
    fun `resource packs and both kinds of shader go to their folders whatever the pack runs`() {
        for (loader in listOf("neoforge", "fabric", "")) {
            assertEquals(into(ContentKind.ResourcePack), placementFor(listOf("minecraft"), loader, "1.21.1"))
            assertEquals(into(ContentKind.ShaderPack), placementFor(listOf("iris", "optifine"), loader, "1.21.1"))
            assertEquals(into(ContentKind.ShaderPack), placementFor(listOf("optifine"), loader, "1.21.1"))
            assertEquals(into(ContentKind.ResourcePack), placementFor(listOf("canvas"), loader, "1.21.1"))
            assertEquals(into(ContentKind.ResourcePack), placementFor(listOf("vanilla"), loader, "1.21.1"))
        }
    }

    @Test
    fun `what has no place says why`() {
        assertEquals(refused(PlacementRefusal.WrongLoader), placementFor(listOf("fabric"), "neoforge", "1.21.1"))
        assertEquals(refused(PlacementRefusal.WrongLoader), placementFor(listOf("fabric"), "", "1.21.1"))
        assertEquals(refused(PlacementRefusal.Datapack), placementFor(listOf("datapack"), "neoforge", "1.21.1"))
        assertEquals(refused(PlacementRefusal.Modpack), placementFor(listOf("mrpack"), "neoforge", "1.21.1"))
        assertEquals(refused(PlacementRefusal.Plugin), placementFor(listOf("paper", "spigot"), "neoforge", "1.21.1"))
        assertEquals(refused(PlacementRefusal.Unknown), placementFor(emptyList(), "neoforge", "1.21.1"))
    }

    /**
     * The catalogue's v2 API reports a modpack build under the loaders its pack runs,
     * so read by loaders a Fabric modpack is a Fabric mod. Its file is what it is.
     */
    @Test
    fun `a modpack build listed under its pack's loaders is still a modpack`() {
        val build = ModrinthVersion(
            id = "mp-1", projectId = "mp", name = "1", versionNumber = "1", versionType = "release",
            gameVersions = listOf("1.21.1"), loaders = listOf("fabric"), datePublished = "2026-01-01T00:00:00Z",
            files = listOf(ModrinthFile(ModrinthHashes("ab"), "https://cdn.test/mp.mrpack", "Pack 1.0.mrpack", primary = true, size = 1)),
        )

        assertEquals(refused(PlacementRefusal.Modpack), build.placementIn("fabric", "1.21.1"))
        assertEquals(null, chooseBuild(listOf(build), "1.21.1", "fabric"), "nothing a pack could take")
    }

    @Test
    fun `the loaders a pack takes mods for`() {
        assertEquals(listOf("quilt", "fabric"), acceptedLoaders("quilt", "1.21.1"))
        assertEquals(listOf("neoforge", "forge"), acceptedLoaders("neoforge", "1.20.1"))
        assertEquals(listOf("neoforge"), acceptedLoaders("neoforge", "1.20.2"))
        assertEquals(listOf("forge"), acceptedLoaders("cleanroom", "1.12.2"))
        assertEquals(listOf("forge"), acceptedLoaders("lwjgl3ify", "1.12.2"))
        assertEquals(listOf("legacy-fabric"), acceptedLoaders("Legacy-Fabric", "1.8.9"))
        assertEquals(emptyList(), acceptedLoaders("vanilla", "1.21.1"))
        assertEquals(emptyList(), acceptedLoaders("", "1.21.1"))
    }
}
