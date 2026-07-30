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
