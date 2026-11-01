package hivens.core.update

import hivens.core.data.FileManifest
import hivens.core.data.flatten
import kotlinx.serialization.Serializable

/**
 * A binary patch offered for one file: apply it to the local file whose hash is
 * [fromSha1] to reproduce the target file [toSha1]. Produced by CI between two
 * releases, applied client-side (bspatch), so a changed file downloads as a small
 * delta instead of in full. Serialized into a delta bundle's `patches.json`.
 */
@Serializable
data class LauncherPatch(
    val path: String,
    val fromSha1: String,
    val toSha1: String,
    val patchSize: Long = 0,
)

/** What the update does with one file in the managed install layout. */
sealed interface FileAction {
    val path: String

    /** Download the small patch and bspatch the local file into the target. */
    data class Patch(override val path: String, val patch: LauncherPatch) : FileAction

    /** Download the whole target file (added file, or no applicable patch). */
    data class Download(override val path: String) : FileAction

    /** Remove a file the target no longer ships. */
    data class Delete(override val path: String) : FileAction
}

/**
 * The pure plan for one launcher self-update: per-file, patch vs whole download vs
 * delete. No IO -- the stage/apply steps execute it.
 */
data class LauncherUpdatePlan(val actions: List<FileAction>) {
    val patches: List<FileAction.Patch> get() = actions.filterIsInstance<FileAction.Patch>()
    val downloads: List<FileAction.Download> get() = actions.filterIsInstance<FileAction.Download>()
    val deletes: List<FileAction.Delete> get() = actions.filterIsInstance<FileAction.Delete>()
    val isEmpty: Boolean get() = actions.isEmpty()
    val changeCount: Int get() = actions.size
}

/**
 * Builds the two-way plan from the installed [local] manifest to the [remote] target.
 *
 * The managed install layout has no user-editable files, so the pack three-way
 * reconcile degrades to a plain add/update/delete diff by passing the local manifest
 * as both baseline and current -- no conflicts, no user-edit protection: the target
 * always wins.
 *
 * For each changed file a patch is chosen only when [patches] offers one whose source
 * matches the local file AND whose target matches the remote file (both ends pinned,
 * so a stale patch can never be applied); otherwise the whole file is downloaded. An
 * added file has no local source, so it is always a whole download.
 */
object LauncherUpdatePlanner {
    fun plan(
        local: FileManifest,
        remote: FileManifest,
        patches: Map<String, LauncherPatch> = emptyMap(),
    ): LauncherUpdatePlan {
        val diff = UpdateReconciler.reconcile(baseline = local, target = remote, current = local)
        val localFlat = local.flatten()
        val remoteFlat = remote.flatten()

        val actions = ArrayList<FileAction>(diff.changeCount)
        for (path in diff.toAdd) actions += FileAction.Download(path)
        for (path in diff.toUpdate) {
            val patch = patches[path]
            val localSha = localFlat[path]?.sha1
            val remoteSha = remoteFlat[path]?.sha1
            val usable = patch != null &&
                !localSha.isNullOrEmpty() && patch.fromSha1.equals(localSha, ignoreCase = true) &&
                !remoteSha.isNullOrEmpty() && patch.toSha1.equals(remoteSha, ignoreCase = true)
            actions += if (usable) FileAction.Patch(path, patch) else FileAction.Download(path)
        }
        for (path in diff.toDelete) actions += FileAction.Delete(path)
        return LauncherUpdatePlan(actions)
    }
}
