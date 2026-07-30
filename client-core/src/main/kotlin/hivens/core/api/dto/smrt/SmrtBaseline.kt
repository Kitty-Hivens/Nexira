package hivens.core.api.dto.smrt

import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.fileManifestOf

/**
 * Builds the update BASELINE ([hivens.core.data.PackInstance.installedManifest])
 * from the manifest a mirror install just laid down: every mod at
 * `mods/<filename>` and every asset at its `dest`, each with the manifest's sha1
 * and size. This is the "what the pack shipped" side that
 * [hivens.core.update.UpdateReconciler] diffs a future target manifest against.
 *
 * Canonical (enabled) paths only: an optional mod installed disabled still
 * records as `mods/<filename>`, because the baseline captures the manifest's
 * identity, not the on-disk `.disabled` layout -- the reconcile maps disk state
 * back to canonical paths itself. [FileData.md5] is left empty; mirror entries
 * carry sha1, which is what the reconciler compares.
 */
fun SmrtPackManifest.toBaselineManifest(): FileManifest {
    val entries = LinkedHashMap<String, FileData>()
    for (mod in mods) {
        entries["mods/${mod.filename}"] = FileData(size = mod.sizeBytes, sha1 = mod.sha1)
    }
    for (asset in assets) {
        entries[asset.dest] = FileData(size = asset.sizeBytes, sha1 = asset.sha1)
    }
    return fileManifestOf(entries)
}
