package hivens.core.update

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.data.FileManifest
import hivens.core.data.flatten

/**
 * Identity-aware mod half of [UpdateReconciler]. Where UpdateReconciler keys
 * every path as a string, a mod carries its version in the filename, so a mod
 * that changes BOTH version and filename (JEI.jar -> jei.jar) reads to a path
 * diff as two unrelated files: an add of the new name and a delete of the old.
 *
 * That lands correctly for an untouched jar (old deleted, new added), but not
 * for a user-edited one: the old file's hash no longer matches the baseline, so
 * the reverse pass keeps it, and the new name is added beside it -- two jars of
 * the same mod, which FML rejects as a duplicate. Keying by
 * [SmrtModEntry.stableKey] ties the two names to one identity: a clean rename is
 * delete-old + add-new, and an edited one becomes a conflict (the user's jar
 * stays, the pack's parks as `<path>.new`) instead of a duplicate.
 *
 * The on/off choice is a separate, already stable-keyed concern (see
 * `OptionalContentRules`); this only fixes the file operations.
 *
 * Baseline identity is not in [hivens.core.data.PackInstance.installedManifest]
 * (it stores paths + hashes only), so the caller supplies the installed
 * version's manifest mods, fetched from the mirror. When that is unavailable the
 * caller falls back to the path-keyed [UpdateReconciler] -- today's behaviour.
 */
fun reconcileMods(
    baselineMods: List<SmrtModEntry>,
    targetMods: List<SmrtModEntry>,
    current: FileManifest,
    isProtected: (String) -> Boolean = { false },
): UpdatePlan {
    val cur = current.flatten()
    val base = baselineMods.associateBy { it.stableKey }
    val targetKeys = targetMods.mapTo(HashSet()) { it.stableKey }
    val targetPaths = targetMods.mapTo(HashSet()) { "mods/${it.filename}" }

    val toAdd = ArrayList<String>()
    val toUpdate = ArrayList<String>()
    val toDelete = ArrayList<String>()
    val conflicts = ArrayList<String>()
    val skipped = ArrayList<String>()

    fun pathOf(m: SmrtModEntry) = "mods/${m.filename}"

    // Forward: everything the target version wants on disk, matched by identity.
    for (t in targetMods) {
        val tPath = pathOf(t)
        if (isProtected(tPath)) { skipped += tPath; continue }
        val b = base[t.stableKey]

        if (b == null || b.filename == t.filename) {
            // A new identity, or the same one at the same path -> plain 3-way at tPath.
            val onDisk = cur[tPath]?.sha1
            when {
                onDisk == null -> toAdd += tPath
                hashEq(onDisk, t.sha1) -> Unit // already up to date
                else -> {
                    val baseHash = b?.sha1
                    val userEdited = !baseHash.isNullOrEmpty() && !hashEq(onDisk, baseHash)
                    val packChanged = baseHash.isNullOrEmpty() || !hashEq(t.sha1, baseHash)
                    if (userEdited && packChanged) conflicts += tPath else toUpdate += tPath
                }
            }
        } else {
            // Same identity, renamed: the on-disk jar sits at the OLD path.
            val bPath = pathOf(b)
            val onOld = cur[bPath]?.sha1
            val userEdited = !onOld.isNullOrEmpty() && !hashEq(onOld, b.sha1)
            if (userEdited) {
                // Keep the user's edited jar; the pack's new one parks as `<new>.new`.
                conflicts += tPath
            } else {
                if (onOld != null) toDelete += bPath // retire the old-named jar
                val onNew = cur[tPath]?.sha1
                when {
                    onNew == null -> toAdd += tPath
                    hashEq(onNew, t.sha1) -> Unit
                    else -> toUpdate += tPath
                }
            }
        }
    }

    // Reverse: mods the baseline shipped that the target dropped, matched by identity.
    for (b in baselineMods) {
        if (b.stableKey in targetKeys) continue
        val bPath = pathOf(b)
        // An identity can be re-keyed without the file changing: stableKey falls
        // back to the filename, so the mirror adding a slug (or a Modrinth source)
        // to an existing entry gives the same jar a new key between builds. The
        // forward pass then sees an unknown key over an already-correct jar and
        // emits nothing, and without this guard the reverse pass would delete a
        // mod the target still ships -- an FML mismatch and a rejected join, with
        // nothing left to re-add it. UpdateReconciler's reverse pass has always
        // had the equivalent check.
        if (bPath in targetPaths) continue
        if (isProtected(bPath)) { skipped += bPath; continue }
        val onDisk = cur[bPath]?.sha1 ?: continue // already gone
        if (hashEq(onDisk, b.sha1)) toDelete += bPath
        // else: the user edited a now-removed mod -> keep it.
    }

    return UpdatePlan(toAdd, toUpdate, toDelete, conflicts, skipped)
}

/**
 * Combine an identity-reconciled mod plan with a path-reconciled asset plan.
 * Mod paths (`mods/...`) and asset dests are disjoint, so the lists concatenate
 * without overlap.
 */
fun UpdatePlan.mergedWith(other: UpdatePlan): UpdatePlan = UpdatePlan(
    toAdd = toAdd + other.toAdd,
    toUpdate = toUpdate + other.toUpdate,
    toDelete = toDelete + other.toDelete,
    conflicts = conflicts + other.conflicts,
    skippedProtected = skippedProtected + other.skippedProtected,
)

private fun hashEq(a: String?, b: String?): Boolean =
    !a.isNullOrEmpty() && a.equals(b, ignoreCase = true)
