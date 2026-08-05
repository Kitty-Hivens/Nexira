package hivens.core.update

import hivens.core.data.FileManifest
import hivens.core.data.flatten

/**
 * The pure plan for moving an instance from its installed BASELINE to a TARGET pack
 * version, given the CURRENT on-disk state. No IO -- the launcher executes the lists.
 * A file present on disk but absent from BOTH baseline and target is user-added and
 * appears in no list (never touched).
 */
data class UpdatePlan(
    /** In target, absent on disk -> download + write. */
    val toAdd: List<String> = emptyList(),
    /** On disk + in target with a different hash, and NOT a user edit -> overwrite. */
    val toUpdate: List<String> = emptyList(),
    /** In the baseline, dropped by target, unchanged by the user -> safe to delete. */
    val toDelete: List<String> = emptyList(),
    /** User edited it AND the pack changed it -> keep the user's, drop the pack's as `<path>.new`. */
    val conflicts: List<String> = emptyList(),
    /** Pack wants to touch a protected (user-config) path -> left alone. */
    val skippedProtected: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = toAdd.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty() && conflicts.isEmpty()

    /** Total files the apply step would write or remove. */
    val changeCount: Int get() = toAdd.size + toUpdate.size + toDelete.size + conflicts.size
}

/**
 * Set-diffs a pack update. The hash-triple (BASE = [baseline], THEIRS = [target],
 * MINE = [current]) decides each path:
 *  - target-only            -> add
 *  - target != disk, mine==base (or no base) -> update (safe overwrite)
 *  - target != disk, mine!=base AND theirs==base -> keep mine, in no list
 *  - target != disk, mine!=base AND theirs!=base -> conflict (keep mine, pack's as .new)
 *  - baseline-only, mine==base -> delete (safe); mine!=base -> keep (user edit survives)
 *  - protected path -> skipped, never written or deleted
 *
 * This reconciles ASSETS -- configs, resource packs, the server list. Mod jars go
 * through [reconcileMods], which deliberately answers the mine!=base/theirs==base
 * case the other way: a jar is what the loader executes, so it belongs to the
 * manifest rather than to whoever last wrote to `mods/`.
 */
object UpdateReconciler {
    fun reconcile(
        baseline: FileManifest?,
        target: FileManifest,
        current: FileManifest,
        isProtected: (String) -> Boolean = { false },
    ): UpdatePlan {
        val base = baseline?.flatten() ?: emptyMap()
        val tgt = target.flatten()
        val cur = current.flatten()

        val toAdd = ArrayList<String>()
        val toUpdate = ArrayList<String>()
        val toDelete = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val skippedProtected = ArrayList<String>()

        // Forward pass: everything the target version wants on disk.
        for ((path, want) in tgt) {
            if (isProtected(path)) { skippedProtected += path; continue }
            val onDisk = cur[path]
            when {
                onDisk == null -> toAdd += path
                hashEq(onDisk.sha1, want.sha1) -> Unit // already up to date
                else -> {
                    val baseHash = base[path]?.sha1
                    val userEdited = !baseHash.isNullOrEmpty() && !hashEq(onDisk.sha1, baseHash)
                    val packChanged = baseHash.isNullOrEmpty() || !hashEq(want.sha1, baseHash)
                    when {
                        // THEIRS == BASE: the pack is shipping the same bytes it always
                        // did, so the difference on disk is entirely the user's. There
                        // is nothing to merge and nothing to warn about -- writing the
                        // target here would revert an edit the update never asked to
                        // touch, which is what made the protected-path list look load
                        // bearing.
                        userEdited && !packChanged -> Unit
                        userEdited -> conflicts += path
                        else -> toUpdate += path
                    }
                }
            }
        }

        // Reverse pass: files the baseline shipped that the target dropped.
        for ((path, baseData) in base) {
            if (path in tgt) continue
            if (isProtected(path)) { skippedProtected += path; continue }
            val onDisk = cur[path] ?: continue          // already gone
            if (hashEq(onDisk.sha1, baseData.sha1)) toDelete += path
            // else: the user modified a now-removed file -> keep it.
        }

        return UpdatePlan(toAdd, toUpdate, toDelete, conflicts, skippedProtected)
    }

    private fun hashEq(a: String?, b: String?): Boolean =
        !a.isNullOrEmpty() && a.equals(b, ignoreCase = true)
}
