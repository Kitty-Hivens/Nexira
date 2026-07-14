package hivens.core.update

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateReconcilerTest {

    // flatten() treats a slashed file key verbatim, so flat manifests are enough here.
    private fun mf(vararg files: Pair<String, String>) =
        FileManifest(files = files.associate { (p, h) -> p to FileData(sha1 = h) })

    @Test
    fun addsTargetFileAbsentOnDisk() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf(),
            target = mf("mods/new.jar" to "h1"),
            current = mf(),
        )
        assertEquals(listOf("mods/new.jar"), plan.toAdd)
        assertTrue(plan.toUpdate.isEmpty() && plan.toDelete.isEmpty() && plan.conflicts.isEmpty())
    }

    @Test
    fun upToDateFileIsNoOp() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("mods/a.jar" to "h1"),
            target = mf("mods/a.jar" to "h1"),
            current = mf("mods/a.jar" to "h1"),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun updatesWhenPackChangedAndUserDidNot() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("mods/a.jar" to "old"),
            target = mf("mods/a.jar" to "new"),
            current = mf("mods/a.jar" to "old"), // disk == baseline -> not user-edited
        )
        assertEquals(listOf("mods/a.jar"), plan.toUpdate)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun conflictWhenBothUserAndPackChanged() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("config/x.cfg" to "base"),
            target = mf("config/x.cfg" to "theirs"),
            current = mf("config/x.cfg" to "mine"), // disk != baseline -> user-edited
        )
        assertEquals(listOf("config/x.cfg"), plan.conflicts)
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test
    fun deletesDroppedFileOnlyWhenUntouched() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("mods/old.jar" to "h1"),
            target = mf(),
            current = mf("mods/old.jar" to "h1"), // unchanged -> safe to delete
        )
        assertEquals(listOf("mods/old.jar"), plan.toDelete)
    }

    @Test
    fun keepsDroppedFileTheUserModified() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("mods/old.jar" to "h1"),
            target = mf(),
            current = mf("mods/old.jar" to "h1-edited"), // user touched it -> keep
        )
        assertTrue(plan.toDelete.isEmpty())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun neverTouchesUserAddedFiles() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("mods/a.jar" to "h1"),
            target = mf("mods/a.jar" to "h1"),
            current = mf("mods/a.jar" to "h1", "mods/zz-myown.jar" to "x"), // not in base/target
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun protectedPathIsSkippedBothWays() {
        val plan = UpdateReconciler.reconcile(
            baseline = mf("options.txt" to "base"),
            target = mf("options.txt" to "theirs"),
            current = mf("options.txt" to "mine"),
            isProtected = { it == "options.txt" },
        )
        assertEquals(listOf("options.txt"), plan.skippedProtected)
        assertTrue(plan.toUpdate.isEmpty() && plan.conflicts.isEmpty() && plan.toDelete.isEmpty())
    }

    @Test
    fun compatClassification() {
        val installed = CachedManifestSnapshot("1.20.1", "fabric", "0.15.0", 17)
        assertEquals(CompatChange.Same, classifyCompat(installed, "1.20.1", "fabric", "0.15.0"))
        assertEquals(CompatChange.LoaderBump, classifyCompat(installed, "1.20.1", "fabric", "0.16.0"))
        assertEquals(CompatChange.McBump, classifyCompat(installed, "1.21", "fabric", "0.15.0"))
        assertEquals(CompatChange.LoaderSwap, classifyCompat(installed, "1.20.1", "forge", "47.0.0"))
        assertEquals(CompatChange.Unknown, classifyCompat(null, "1.20.1", "fabric", "0.15.0"))
        assertTrue(CompatChange.Same.isSafe && CompatChange.LoaderBump.isSafe)
        assertTrue(!CompatChange.McBump.isSafe && !CompatChange.LoaderSwap.isSafe)
    }
}
