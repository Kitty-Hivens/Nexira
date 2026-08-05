package hivens.core.update

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModReconcilerTest {

    // A Modrinth-sourced mod: stableKey is `modrinth:<project>`, stable across a
    // version bump even as filename + sha1 change.
    private fun mod(filename: String, sha1: String, project: String) =
        SmrtModEntry(
            filename = filename,
            sha1 = sha1,
            sizeBytes = 1,
            source = SmrtSource.Modrinth(projectId = project, versionId = "v-$sha1"),
        )

    private fun disk(vararg files: Pair<String, String>) =
        FileManifest(files = files.associate { (p, h) -> p to FileData(sha1 = h) })

    /** A curator-slugged entry: stableKey is the slug, not the filename. */
    private fun slugged(filename: String, sha1: String, slug: String) =
        SmrtModEntry(
            filename = filename,
            sha1 = sha1,
            sizeBytes = 1,
            slug = slug,
            source = SmrtSource.SmrtStatic(url = "https://example/$filename"),
        )

    /** No slug, no Modrinth source -- stableKey falls back to the filename. */
    private fun unkeyed(filename: String, sha1: String) =
        SmrtModEntry(
            filename = filename,
            sha1 = sha1,
            sizeBytes = 1,
            source = SmrtSource.SmrtStatic(url = "https://example/$filename"),
        )

    @Test
    fun reKeyingAnEntryDoesNotDeleteTheJarTheTargetStillShips() {
        // The mirror adds a slug to an entry that had none, exactly as SmrtModEntry's
        // own KDoc asks it to. Same filename, same bytes -- only the identity key
        // moved, from the filename to the slug.
        val plan = reconcileMods(
            baselineMods = listOf(unkeyed("industrialcraft-2.8.jar", "h1")),
            targetMods = listOf(slugged("industrialcraft-2.8.jar", "h1", slug = "ic2")),
            current = disk("mods/industrialcraft-2.8.jar" to "h1"),
        )
        // The forward pass has nothing to do (the jar is already correct); the
        // reverse pass must not read the re-key as a removal.
        assertTrue(plan.toDelete.isEmpty(), "deleted a mod the target ships: ${plan.toDelete}")
        assertTrue(plan.toAdd.isEmpty() && plan.toUpdate.isEmpty() && plan.conflicts.isEmpty())
    }

    @Test
    fun reKeyingStillUpdatesWhenTheBytesChanged() {
        val plan = reconcileMods(
            baselineMods = listOf(unkeyed("industrialcraft-2.8.jar", "h1")),
            targetMods = listOf(slugged("industrialcraft-2.8.jar", "h2", slug = "ic2")),
            current = disk("mods/industrialcraft-2.8.jar" to "h1"),
        )
        assertEquals(listOf("mods/industrialcraft-2.8.jar"), plan.toUpdate)
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun aGenuinelyDroppedModIsStillDeleted() {
        // The guard keys on the path the target ships, so a mod the target really
        // dropped must still be retired.
        val plan = reconcileMods(
            baselineMods = listOf(unkeyed("oldmod-1.0.jar", "h1"), unkeyed("keep-1.0.jar", "h2")),
            targetMods = listOf(unkeyed("keep-1.0.jar", "h2")),
            current = disk("mods/oldmod-1.0.jar" to "h1", "mods/keep-1.0.jar" to "h2"),
        )
        assertEquals(listOf("mods/oldmod-1.0.jar"), plan.toDelete)
    }

    @Test
    fun sameNameBumpIsAnUpdate() {
        val plan = reconcileMods(
            baselineMods = listOf(mod("jei.jar", "old", "u6dRKJwZ")),
            targetMods = listOf(mod("jei.jar", "new", "u6dRKJwZ")),
            current = disk("mods/jei.jar" to "old"),
        )
        assertEquals(listOf("mods/jei.jar"), plan.toUpdate)
        assertTrue(plan.toAdd.isEmpty() && plan.toDelete.isEmpty() && plan.conflicts.isEmpty())
    }

    @Test
    fun cleanRenameDeletesOldAndAddsNew() {
        // JEI.jar -> jei.jar, same Modrinth project, untouched on disk.
        val plan = reconcileMods(
            baselineMods = listOf(mod("JEI.jar", "h368", "u6dRKJwZ")),
            targetMods = listOf(mod("jei.jar", "h370", "u6dRKJwZ")),
            current = disk("mods/JEI.jar" to "h368"),
        )
        assertEquals(listOf("mods/jei.jar"), plan.toAdd)
        assertEquals(listOf("mods/JEI.jar"), plan.toDelete)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun editedRenameIsAConflictNotADuplicate() {
        // The bug: user edited JEI.jar, the pack renames it to jei.jar. Path-keyed
        // reconcile would keep the edited JEI.jar AND add jei.jar -> duplicate mod.
        // Identity-keyed: the old jar stays, the new parks as .new, no duplicate.
        val plan = reconcileMods(
            baselineMods = listOf(mod("JEI.jar", "h368", "u6dRKJwZ")),
            targetMods = listOf(mod("jei.jar", "h370", "u6dRKJwZ")),
            current = disk("mods/JEI.jar" to "user-edited"),
        )
        assertEquals(listOf("mods/jei.jar"), plan.conflicts)
        // crucially: the old edited jar is NOT deleted and the new is NOT added
        // active -- no two live jars of one mod.
        assertTrue(plan.toDelete.isEmpty(), "old edited jar kept, not deleted: ${plan.toDelete}")
        assertTrue(plan.toAdd.isEmpty(), "new jar parks as .new via conflict, not added active: ${plan.toAdd}")
    }

    @Test
    fun newIdentityIsAnAdd() {
        val plan = reconcileMods(
            baselineMods = emptyList(),
            targetMods = listOf(mod("newmod.jar", "h1", "PROJ")),
            current = disk(),
        )
        assertEquals(listOf("mods/newmod.jar"), plan.toAdd)
    }

    @Test
    fun droppedIdentityDeletesWhenUntouched() {
        val plan = reconcileMods(
            baselineMods = listOf(mod("gone.jar", "h1", "GONE")),
            targetMods = emptyList(),
            current = disk("mods/gone.jar" to "h1"),
        )
        assertEquals(listOf("mods/gone.jar"), plan.toDelete)
    }

    @Test
    fun droppedButUserEditedIsKept() {
        val plan = reconcileMods(
            baselineMods = listOf(mod("gone.jar", "h1", "GONE")),
            targetMods = emptyList(),
            current = disk("mods/gone.jar" to "user-edited"),
        )
        assertTrue(plan.isEmpty, "an edited removed mod is left alone: $plan")
    }

    @Test
    fun mergedWithConcatenatesDisjointPlans() {
        val mods = UpdatePlan(toAdd = listOf("mods/a.jar"), toDelete = listOf("mods/b.jar"))
        val assets = UpdatePlan(toUpdate = listOf("config/x.cfg"))
        val merged = mods.mergedWith(assets)
        assertEquals(listOf("mods/a.jar"), merged.toAdd)
        assertEquals(listOf("mods/b.jar"), merged.toDelete)
        assertEquals(listOf("config/x.cfg"), merged.toUpdate)
    }
}
