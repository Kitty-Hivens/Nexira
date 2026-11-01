package hivens.core.update

import hivens.core.data.FileData
import hivens.core.data.fileManifestOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherUpdatePlanTest {

    private fun manifest(vararg entries: Pair<String, String>) =
        fileManifestOf(entries.associate { (path, sha) -> path to FileData(sha1 = sha) })

    private fun plan(
        local: List<Pair<String, String>>,
        remote: List<Pair<String, String>>,
        patches: List<LauncherPatch> = emptyList(),
    ) = LauncherUpdatePlanner.plan(
        local = manifest(*local.toTypedArray()),
        remote = manifest(*remote.toTypedArray()),
        patches = patches.associateBy { it.path },
    )

    @Test
    fun identicalManifestsProduceNoWork() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "aaa", "runtime/bin/java" to "bbb"),
            remote = listOf("lib/nexira.jar" to "aaa", "runtime/bin/java" to "bbb"),
        )
        assertTrue(p.isEmpty)
    }

    @Test
    fun addedFileIsAlwaysAWholeDownload() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "aaa"),
            remote = listOf("lib/nexira.jar" to "aaa", "natives/libnew.so" to "ccc"),
            // Even if a bogus patch is offered for an added file, it can't apply (no local source).
            patches = listOf(LauncherPatch("natives/libnew.so", fromSha1 = "x", toSha1 = "ccc")),
        )
        assertEquals(listOf("natives/libnew.so"), p.downloads.map { it.path })
        assertTrue(p.patches.isEmpty())
    }

    @Test
    fun changedFileWithMatchingPatchIsPatched() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "old"),
            remote = listOf("lib/nexira.jar" to "new"),
            patches = listOf(LauncherPatch("lib/nexira.jar", fromSha1 = "old", toSha1 = "new")),
        )
        assertEquals(listOf("lib/nexira.jar"), p.patches.map { it.path })
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun changedFileWithWrongSourcePatchFallsBackToDownload() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "old"),
            remote = listOf("lib/nexira.jar" to "new"),
            // fromSha does not match the local file -> cannot apply.
            patches = listOf(LauncherPatch("lib/nexira.jar", fromSha1 = "someOtherBase", toSha1 = "new")),
        )
        assertEquals(listOf("lib/nexira.jar"), p.downloads.map { it.path })
        assertTrue(p.patches.isEmpty())
    }

    @Test
    fun stalePatchToWrongTargetIsRejected() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "old"),
            remote = listOf("lib/nexira.jar" to "new"),
            // toSha does not match the remote target (stale patch) -> reject, download whole.
            patches = listOf(LauncherPatch("lib/nexira.jar", fromSha1 = "old", toSha1 = "staleTarget")),
        )
        assertEquals(listOf("lib/nexira.jar"), p.downloads.map { it.path })
        assertTrue(p.patches.isEmpty())
    }

    @Test
    fun droppedFileIsDeleted() {
        val p = plan(
            local = listOf("lib/nexira.jar" to "aaa", "agents/old-agent.jar" to "ddd"),
            remote = listOf("lib/nexira.jar" to "aaa"),
        )
        assertEquals(listOf("agents/old-agent.jar"), p.deletes.map { it.path })
    }

    @Test
    fun mixedUpdatePicksTheRightActionPerFile() {
        val p = plan(
            local = listOf(
                "lib/nexira.jar" to "jarOld",
                "runtime/bin/java" to "rt",     // unchanged
                "agents/gone.jar" to "gone",    // removed
            ),
            remote = listOf(
                "lib/nexira.jar" to "jarNew",   // changed, patchable
                "runtime/bin/java" to "rt",     // unchanged
                "natives/added.so" to "add",    // added
            ),
            patches = listOf(LauncherPatch("lib/nexira.jar", fromSha1 = "jarOld", toSha1 = "jarNew")),
        )
        assertEquals(listOf("lib/nexira.jar"), p.patches.map { it.path })
        assertEquals(listOf("natives/added.so"), p.downloads.map { it.path })
        assertEquals(listOf("agents/gone.jar"), p.deletes.map { it.path })
        assertEquals(3, p.changeCount)
    }
}
